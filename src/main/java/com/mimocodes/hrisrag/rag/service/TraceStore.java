package com.mimocodes.hrisrag.rag.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Persists one row per request. Stores ids, scores, timings, usage and decisions, but NOT the retrieved text, the
 * context or the system prompt (only its version): traces stay small and do not duplicate restricted content.
 * Per-passage guardrail outcomes (retrieved / flagged / redacted / removed / denied, with rule ids) and the separate
 * embedding and chat call counts are stored for the benchmark.
 */
@Repository
public class TraceStore {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JdbcTemplate jdbc;

	public TraceStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void save(RagQuery query, RagResult r) {
		Map<String, Object> trace = new LinkedHashMap<>();
		trace.put("settings", r.settings());
		trace.put("citations", r.citations());
		trace.put("retrieved", r.retrieved().stream().map(TraceStore::chunkSummary).toList());
		trace.put("passageChecks", r.passageChecks());
		trace.put("contextChars", r.contextSent() == null ? 0 : r.contextSent().length());
		trace.put("modelCalls", r.modelCalls());
		trace.put("callCounts", r.callCounts());
		trace.put("toolCalls", r.toolCalls()); // names, validated arguments, decisions; never the result payloads
		trace.put("user", Map.of("role", query.user().role(), "department", query.user().department(),
				"maxAccessLevel", query.user().maxAccessLevel()));
		trace.put("guardrails", r.guardrails());
		trace.put("timings", r.timings());
		jdbc.update("""
				INSERT INTO rag_request_trace (request_id, conversation_id, user_code, strategy, question, answer_status,
				  answer, prompt_template_version, total_ms, trace, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""",
				r.requestId(), query.conversationId(), query.user().employeeCode(), r.strategy().name(),
				truncate(query.question(), 4000), r.status().name(), r.answer(), r.promptVersion(),
				(int) r.timings().totalMs(), JSON.writeValueAsString(trace), Timestamp.from(Instant.now()));
	}

	public List<Map<String, Object>> recent(int limit) {
		return jdbc.queryForList("SELECT request_id, strategy, answer_status, total_ms, created_at FROM rag_request_trace "
				+ "ORDER BY created_at DESC LIMIT ?", limit);
	}

	/** Ids, scores and ranks of a retrieved chunk (no text). Vector/keyword ranks are null except for hybrid. */
	public static Map<String, Object> chunkSummary(com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk c) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("rank", c.rank());
		m.put("chunkId", c.chunkId());
		m.put("docKey", c.docKey());
		m.put("version", c.version());
		m.put("section", c.sectionPath());
		m.put("score", c.score());
		m.put("vectorRank", c.vectorRank());
		m.put("keywordRank", c.keywordRank());
		return m;
	}

	private static String truncate(String s, int max) {
		return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
	}
}
