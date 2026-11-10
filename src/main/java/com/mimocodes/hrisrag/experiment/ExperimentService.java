package com.mimocodes.hrisrag.experiment;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.mimocodes.hrisrag.ai.EmbeddingService;
import com.mimocodes.hrisrag.benchmark.BenchmarkCase;
import com.mimocodes.hrisrag.benchmark.BenchmarkDataset;
import com.mimocodes.hrisrag.document.corpus.CorpusSpec;
import com.mimocodes.hrisrag.evaluation.CaseScorer;
import com.mimocodes.hrisrag.evaluation.MetricAggregator;
import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.RagSettings;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.guardrail.GuardrailProperties;
import com.mimocodes.hrisrag.rag.prompt.GroundedPrompt;
import com.mimocodes.hrisrag.rag.service.RagService;
import com.mimocodes.hrisrag.rag.service.TraceStore;
import com.mimocodes.hrisrag.security.AppUserStore;

/**
 * Runs benchmark cases through the SAME {@link RagService} as the chat API and stores raw results plus aggregates.
 * <ul>
 * <li>Only the question text and the asker's identity enter the answering path; labels are used afterwards for scoring
 * (architecture rule 3).</li>
 * <li>Resumable: (run, case) is unique; a resumed experiment skips finished cases and reuses its stored filter/limit.</li>
 * <li>Quota-safe: a pause after each chat call; a provider quota error stops the run as PARTIAL without storing that
 * case, so a later resume retries it.</li>
 * </ul>
 */
@Service
public class ExperimentService {

	private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);

	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis);
	}

	/** @param strategy null = {@code hrisrag.benchmark.strategy} */
	public record Request(String caseFilter, int limit, Long resumeExperimentId, String strategy) {
		public Request(String caseFilter, int limit, Long resumeExperimentId) {
			this(caseFilter, limit, resumeExperimentId, null);
		}
	}

	public record Summary(long experimentId, long runId, String strategy, String status, int selected, int alreadyDone, int executed,
			String stopReason, BenchmarkDataset dataset, List<MetricAggregator.Aggregate> aggregates,
			List<ExperimentStore.ResultRow> results) {
	}

	private final RagService rag;
	private final AppUserStore users;
	private final ExperimentStore store;
	private final BenchmarkProperties props;
	private final RagProperties ragProps;
	private final GuardrailProperties guardrailProps;
	private final EmbeddingService embeddings;
	private Sleeper sleeper = ExperimentService::sleepQuietly;

	@Autowired
	public ExperimentService(RagService rag, AppUserStore users, ExperimentStore store, BenchmarkProperties props,
			RagProperties ragProps, GuardrailProperties guardrailProps, EmbeddingService embeddings) {
		this.rag = rag;
		this.users = users;
		this.store = store;
		this.props = props;
		this.ragProps = ragProps;
		this.guardrailProps = guardrailProps;
		this.embeddings = embeddings;
	}

	/** Tests replace the pause. */
	void setSleeper(Sleeper sleeper) {
		this.sleeper = sleeper;
	}

	public Summary run(Request request) {
		BenchmarkDataset ds = BenchmarkDataset.load(Path.of(props.datasetFile()));
		CorpusSpec spec = CorpusSpec.load(Path.of(props.corpusSpecDir()));
		StrategyId strategy = StrategyId.valueOf(request.strategy() != null ? request.strategy() : props.strategy());
		RagSettings settings = ragProps.defaultSettings();
		long datasetId = store.registerDataset(ds);

		long experimentId;
		long runId;
		String filter;
		int limit;
		if (request.resumeExperimentId() != null) {
			var exp = store.experiment(request.resumeExperimentId()).orElseThrow(
					() -> new IllegalArgumentException("experiment " + request.resumeExperimentId() + " not found"));
			if (exp.datasetId() != datasetId) {
				throw new IllegalArgumentException("experiment " + exp.id() + " used a different dataset");
			}
			experimentId = exp.id();
			runId = store.runId(experimentId, strategy.name(), 1).orElseThrow(
					() -> new IllegalStateException("experiment " + exp.id() + " has no " + strategy + " run"));
			filter = exp.caseFilter();
			limit = exp.caseLimit();
			store.markRunning(experimentId);
		}
		else {
			filter = request.caseFilter();
			limit = request.limit();
			experimentId = store.createExperiment(datasetId, filter, limit, config(ds, strategy, settings));
			runId = store.createRun(experimentId, strategy.name(), 1, settings);
		}

		List<BenchmarkCase> selected = select(ds, filter, limit);
		Set<String> done = store.finishedCases(runId);
		int executed = 0;
		String stopReason = null;
		try {
			List<BenchmarkCase> todo = selected.stream().filter(c -> !done.contains(c.id())).toList();
			for (int i = 0; i < todo.size(); i++) {
				BenchmarkCase c = todo.get(i);
				UserContext asker = users.contextForEmployee(c.asker()).orElseThrow(
						() -> new IllegalStateException("no app_user for benchmark asker " + c.asker()));
				// Only the question and the asker reach the answering path.
				RagResult r = rag.ask(strategy, new RagQuery(c.question(), "bench-" + experimentId, asker), settings);
				if (r.guardrails().stream().anyMatch(g -> g.rule().equals("PROVIDER_QUOTA"))) {
					stopReason = "model provider quota or rate limit reached at " + c.id() + "; resume later with "
							+ "--hrisrag.benchmark.resume=" + experimentId;
					log.warn(stopReason);
					break;
				}
				CaseScorer.Outcome o = outcome(r);
				Map<String, Integer> relevant = ds.relevantDocs(c, spec);
				Map<String, Double> metrics = CaseScorer.score(c, relevant, o, settings.topK());
				store.saveResult(runId, c, r.requestId(), o, detail(r, relevant), metrics);
				executed++;
				log.info("[{}/{}] {} {} -> {} pass={}", done.size() + executed, selected.size(), c.id(), c.category(),
						r.status(), metrics.get("case_pass"));
				if (r.callCounts().chatCalls() > 0 && i < todo.size() - 1 && !props.pause().isZero()) {
					sleeper.sleep(props.pause().toMillis());
				}
			}
			String status = stopReason != null ? "PARTIAL" : "COMPLETED";
			List<MetricAggregator.Aggregate> aggregates = recomputeAggregates(runId, ds);
			store.finish(experimentId, status, stopReason);
			return new Summary(experimentId, runId, strategy.name(), status, selected.size(), done.size(), executed, stopReason, ds,
					aggregates, store.results(runId));
		}
		catch (RuntimeException e) {
			log.error("benchmark experiment {} failed", experimentId, e);
			recomputeAggregates(runId, ds);
			store.finish(experimentId, "FAILED", truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500));
			throw e;
		}
	}

	/** Aggregates are always recomputed from ALL stored results of the run (so a resume yields complete numbers). */
	List<MetricAggregator.Aggregate> recomputeAggregates(long runId, BenchmarkDataset ds) {
		List<MetricAggregator.Aggregate> aggregates = MetricAggregator.aggregate(scored(store.results(runId), ds));
		store.replaceAggregates(runId, aggregates);
		return aggregates;
	}

	private static List<MetricAggregator.Scored> scored(List<ExperimentStore.ResultRow> rows, BenchmarkDataset ds) {
		Map<String, BenchmarkCase> byId = new LinkedHashMap<>();
		ds.cases().forEach(c -> byId.put(c.id(), c));
		return rows.stream().map(row -> new MetricAggregator.Scored(row.caseKey(), row.category(), row.language(),
				CaseScorer.scope(byId.get(row.caseKey())), row.metrics())).toList();
	}

	/**
	 * Side-by-side comparison with the first run of a baseline experiment, recomputed over the cases BOTH runs contain
	 * (so different case sets never distort the comparison).
	 */
	public record Comparison(long baselineExperimentId, String baselineStrategy, List<String> commonCases,
			List<MetricAggregator.Aggregate> baseline, List<MetricAggregator.Aggregate> current,
			List<ExperimentStore.ResultRow> baselineRows, List<ExperimentStore.ResultRow> currentRows) {
	}

	public Comparison compare(long baselineExperimentId, Summary current) {
		var runs = store.runs(baselineExperimentId);
		if (runs.isEmpty()) {
			throw new IllegalArgumentException("experiment " + baselineExperimentId + " has no runs");
		}
		long baselineRun = ((Number) runs.getFirst().get("id")).longValue();
		String baselineStrategy = (String) runs.getFirst().get("strategy");
		Set<String> currentKeys = new java.util.HashSet<>();
		current.results().forEach(r -> currentKeys.add(r.caseKey()));
		List<ExperimentStore.ResultRow> base = store.results(baselineRun).stream()
				.filter(r -> currentKeys.contains(r.caseKey())).toList();
		Set<String> common = new java.util.LinkedHashSet<>();
		base.forEach(r -> common.add(r.caseKey()));
		List<ExperimentStore.ResultRow> cur = current.results().stream().filter(r -> common.contains(r.caseKey()))
				.toList();
		return new Comparison(baselineExperimentId, baselineStrategy, List.copyOf(common),
				MetricAggregator.aggregate(scored(base, current.dataset())),
				MetricAggregator.aggregate(scored(cur, current.dataset())), base, cur);
	}

	/** Case ids matching any comma-separated pattern ({@code *} wildcard), in dataset order, then the limit. */
	static List<BenchmarkCase> select(BenchmarkDataset ds, String filter, int limit) {
		List<Pattern> patterns = new ArrayList<>();
		if (filter != null && !filter.isBlank()) {
			for (String p : filter.split(",")) {
				if (!p.isBlank()) {
					patterns.add(Pattern.compile(Pattern.quote(p.strip()).replace("*", "\\E.*\\Q")));
				}
			}
		}
		List<BenchmarkCase> out = ds.cases().stream()
				.filter(c -> patterns.isEmpty() || patterns.stream().anyMatch(p -> p.matcher(c.id()).matches()))
				.toList();
		return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
	}

	private static CaseScorer.Outcome outcome(RagResult r) {
		int screened = (int) r.passageChecks().stream()
				.filter(p -> List.of("FLAGGED", "REDACTED", "REMOVED").contains(p.outcome())).count();
		int denied = (int) r.passageChecks().stream().filter(p -> p.outcome().equals("DENIED")).count();
		var cc = r.callCounts();
		return new CaseScorer.Outcome(r.status().name(), r.answer(),
				r.retrieved().stream().map(c -> c.docKey()).toList(),
				r.citations().stream().map(c -> c.docKey()).toList(), screened, denied, r.timings().totalMs(),
				cc.embeddingCalls(), cc.embeddingCacheHits(), cc.chatCalls(), cc.promptTokens(), cc.completionTokens(),
				cc.thinkingTokens());
	}

	private static Map<String, Object> detail(RagResult r, Map<String, Integer> relevant) {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("relevantDocs", relevant);
		d.put("retrieved", r.retrieved().stream().map(TraceStore::chunkSummary).toList());
		d.put("citations", r.citations());
		d.put("passageChecks", r.passageChecks().stream().filter(p -> !p.outcome().equals("CLEAN")).toList());
		d.put("guardrails", r.guardrails());
		return d;
	}

	private Map<String, Object> config(BenchmarkDataset ds, StrategyId strategy, RagSettings settings) {
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("dataset", Map.of("name", ds.name(), "version", ds.version(), "sha256", ds.sha256(),
				"referenceDate", ds.referenceDate().toString()));
		c.put("strategy", strategy.name());
		c.put("settings", settings);
		if (strategy == StrategyId.HYBRID) {
			c.put("hybrid", ragProps.hybrid());
		}
		c.put("promptVersion", GroundedPrompt.VERSION);
		c.put("embeddingModel", embeddings.modelName());
		c.put("guardrails", guardrailProps);
		c.put("corpus", store.corpusSnapshot());
		c.put("pauseMs", props.pause().toMillis());
		return c;
	}

	private static String truncate(String s, int max) {
		return s == null ? null : s.length() > max ? s.substring(0, max) : s;
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
