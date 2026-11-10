package com.mimocodes.hrisrag.experiment;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.mimocodes.hrisrag.benchmark.BenchmarkCase;
import com.mimocodes.hrisrag.benchmark.BenchmarkDataset;
import com.mimocodes.hrisrag.evaluation.CaseScorer;
import com.mimocodes.hrisrag.evaluation.MetricAggregator;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JDBC persistence for datasets, experiments, runs, per-question results and aggregates (Flyway V6). */
@Repository
public class ExperimentStore {

	static final JsonMapper JSON = JsonMapper.builder().build();

	public record ExperimentRow(long id, long datasetId, String datasetName, String datasetVersion, String status,
			String caseFilter, int caseLimit, Instant startedAt, Instant finishedAt, String stopReason, int results) {
	}

	public record ResultRow(String caseKey, String category, String language, String answerStatus,
			Map<String, Double> metrics) {
	}

	private final JdbcTemplate jdbc;

	public ExperimentStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** A dataset file no longer matches its registered checksum (labels must not change silently). */
	public static class DatasetChangedException extends RuntimeException {
		DatasetChangedException(String message) {
			super(message);
		}
	}

	/** Registers the dataset version, or verifies that the file still matches the registered checksum. */
	public long registerDataset(BenchmarkDataset ds) {
		var existing = jdbc.query("SELECT id, checksum FROM benchmark_dataset WHERE name = ? AND version = ?",
				(rs, i) -> Map.entry(rs.getLong("id"), rs.getString("checksum")), ds.name(), ds.version());
		if (!existing.isEmpty()) {
			var row = existing.getFirst();
			if (!row.getValue().equals(ds.sha256())) {
				throw new DatasetChangedException("dataset " + ds.name() + " v" + ds.version() + " changed since it was "
						+ "registered (checksum " + row.getValue() + " ≠ " + ds.sha256() + "); publish a new version");
			}
			return row.getKey();
		}
		return jdbc.queryForObject("INSERT INTO benchmark_dataset (name, version, checksum, case_count) "
				+ "VALUES (?, ?, ?, ?) RETURNING id", Long.class, ds.name(), ds.version(), ds.sha256(), ds.cases().size());
	}

	public long createExperiment(long datasetId, String filter, int limit, Map<String, Object> config) {
		return jdbc.queryForObject("INSERT INTO experiment (dataset_id, status, case_filter, case_limit, config) "
				+ "VALUES (?, 'RUNNING', ?, ?, ?::jsonb) RETURNING id", Long.class, datasetId,
				filter == null || filter.isBlank() ? null : filter, limit, JSON.writeValueAsString(config));
	}

	public long createRun(long experimentId, String strategy, int trial, Object settings) {
		return jdbc.queryForObject("INSERT INTO experiment_run (experiment_id, strategy, trial, settings) "
				+ "VALUES (?, ?, ?, ?::jsonb) RETURNING id", Long.class, experimentId, strategy, trial,
				JSON.writeValueAsString(settings));
	}

	public Optional<Long> runId(long experimentId, String strategy, int trial) {
		return jdbc.queryForList("SELECT id FROM experiment_run WHERE experiment_id = ? AND strategy = ? AND trial = ?",
				Long.class, experimentId, strategy, trial).stream().findFirst();
	}

	public void markRunning(long experimentId) {
		jdbc.update("UPDATE experiment SET status = 'RUNNING', finished_at = NULL, stop_reason = NULL WHERE id = ?",
				experimentId);
	}

	public void finish(long experimentId, String status, String stopReason) {
		jdbc.update("UPDATE experiment SET status = ?, finished_at = ?, stop_reason = ? WHERE id = ?", status,
				Timestamp.from(Instant.now()), stopReason, experimentId);
	}

	public Set<String> finishedCases(long runId) {
		return new HashSet<>(jdbc.queryForList("SELECT case_key FROM question_result WHERE run_id = ?", String.class,
				runId));
	}

	public void saveResult(long runId, BenchmarkCase c, java.util.UUID requestId, CaseScorer.Outcome o,
			Map<String, Object> detail, Map<String, Double> metrics) {
		jdbc.update("""
				INSERT INTO question_result (run_id, case_key, category, language, expected_behavior, answer_status, answer,
				  request_id, total_ms, embedding_calls, embedding_cache_hits, chat_calls, prompt_tokens, completion_tokens,
				  thinking_tokens, detail, metrics)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
				ON CONFLICT (run_id, case_key) DO NOTHING""",
				runId, c.id(), c.category(), c.language(), c.expectedBehavior(), o.status(), o.answer(), requestId,
				(int) o.totalMs(), o.embeddingCalls(), o.embeddingCacheHits(), o.chatCalls(), o.promptTokens(),
				o.completionTokens(), o.thinkingTokens(), JSON.writeValueAsString(detail),
				JSON.writeValueAsString(metrics));
	}

	public List<ResultRow> results(long runId) {
		return jdbc.query("SELECT case_key, category, language, answer_status, metrics::text AS m FROM question_result "
				+ "WHERE run_id = ? ORDER BY id", (rs, i) -> new ResultRow(rs.getString("case_key"),
						rs.getString("category"), rs.getString("language"), rs.getString("answer_status"),
						JSON.readValue(rs.getString("m"), new TypeReference<Map<String, Double>>() {
						})), runId);
	}

	@Transactional
	public void replaceAggregates(long runId, List<MetricAggregator.Aggregate> aggregates) {
		jdbc.update("DELETE FROM metric_aggregate WHERE run_id = ?", runId);
		jdbc.batchUpdate("INSERT INTO metric_aggregate (run_id, metric, slice_type, slice_value, value, sample_size) "
				+ "VALUES (?, ?, ?, ?, ?, ?)", aggregates, 200, (ps, a) -> {
					ps.setLong(1, runId);
					ps.setString(2, a.metric());
					ps.setString(3, a.sliceType());
					ps.setString(4, a.sliceValue());
					ps.setDouble(5, a.value());
					ps.setInt(6, a.sampleSize());
				});
	}

	public List<MetricAggregator.Aggregate> aggregates(long runId) {
		return jdbc.query("SELECT metric, slice_type, slice_value, value, sample_size FROM metric_aggregate "
				+ "WHERE run_id = ? ORDER BY id", (rs, i) -> new MetricAggregator.Aggregate(rs.getString("metric"),
						rs.getString("slice_type"), rs.getString("slice_value"), rs.getDouble("value"),
						rs.getInt("sample_size")), runId);
	}

	public Optional<ExperimentRow> experiment(long id) {
		return experiments("WHERE e.id = ?", id).stream().findFirst();
	}

	public List<ExperimentRow> recentExperiments(int limit) {
		return experiments("ORDER BY e.id DESC LIMIT ?", limit);
	}

	private List<ExperimentRow> experiments(String tail, Object arg) {
		return jdbc.query("""
				SELECT e.id, e.dataset_id, d.name, d.version, e.status, e.case_filter, e.case_limit, e.started_at,
				       e.finished_at, e.stop_reason,
				       (SELECT count(*) FROM question_result q JOIN experiment_run r ON r.id = q.run_id
				        WHERE r.experiment_id = e.id) AS results
				FROM experiment e JOIN benchmark_dataset d ON d.id = e.dataset_id
				""" + tail, (rs, i) -> new ExperimentRow(rs.getLong("id"), rs.getLong("dataset_id"), rs.getString("name"),
				rs.getString("version"), rs.getString("status"), rs.getString("case_filter"), rs.getInt("case_limit"),
				rs.getTimestamp("started_at").toInstant(),
				rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant(),
				rs.getString("stop_reason"), rs.getInt("results")), arg);
	}

	public List<Map<String, Object>> runs(long experimentId) {
		return jdbc.queryForList("SELECT id, strategy, trial, settings::text AS settings FROM experiment_run "
				+ "WHERE experiment_id = ? ORDER BY id", experimentId);
	}

	public List<Map<String, Object>> resultSummaries(long runId) {
		return jdbc.queryForList("""
				SELECT case_key, category, language, expected_behavior, answer_status, total_ms, chat_calls,
				       embedding_calls, (metrics->>'case_pass')::float AS case_pass,
				       (metrics->>'recall_at_k')::float AS recall_at_k
				FROM question_result WHERE run_id = ? ORDER BY id""", runId);
	}

	/** Corpus snapshot for the experiment config (reproducibility). */
	public Map<String, Object> corpusSnapshot() {
		return jdbc.queryForMap("SELECT (SELECT count(*) FROM source_document) AS documents, "
				+ "(SELECT count(*) FROM document_chunk) AS chunks, "
				+ "(SELECT max(id) FROM ingestion_run) AS latest_ingestion_run");
	}
}
