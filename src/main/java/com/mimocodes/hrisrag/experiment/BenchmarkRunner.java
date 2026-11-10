package com.mimocodes.hrisrag.experiment;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.common.RunReports;
import com.mimocodes.hrisrag.evaluation.MetricAggregator.Aggregate;

/**
 * Command-line benchmark, active only with {@code --hrisrag.benchmark.run=true}. Prints start/finish banners, writes a
 * report to {@code hrisrag.benchmark.report-dir} and exits (0 = COMPLETED, 1 = PARTIAL/FAILED).
 */
@Component
@ConditionalOnProperty(prefix = "hrisrag.benchmark", name = "run", havingValue = "true")
public class BenchmarkRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

	/** Metrics shown in the report, in this order. */
	static final List<String> HEADLINE = List.of("case_pass", "behavior_correct", "must_contain_pass",
			"must_not_contain_violation", "language_match", "answered_with_citation", "citation_precision",
			"recall_at_k", "precision_at_k", "mrr", "ndcg_at_k", "hit_at_k", "security_pass",
			"status_answered_rate", "status_abstained_rate", "status_blocked_rate", "status_error_rate",
			"latency_p50_ms", "latency_p95_ms", "embedding_calls_total", "embedding_cache_hits_total", "chat_calls_total",
			"prompt_tokens_total", "completion_tokens_total", "thinking_tokens_total", "screened_passages_total",
			"denied_passages_total");

	private final ExperimentService service;
	private final BenchmarkProperties props;
	private final ConfigurableApplicationContext context;

	public BenchmarkRunner(ExperimentService service, BenchmarkProperties props, ConfigurableApplicationContext context) {
		this.service = service;
		this.props = props;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			RunReports.banner(log, "BENCHMARK STARTED", "dataset=" + Path.of(props.datasetFile()).toAbsolutePath()
					+ "\nstrategy=" + props.strategy() + " cases=" + (props.cases().isBlank() ? "<all>" : props.cases())
					+ " limit=" + (props.limit() == 0 ? "none" : props.limit())
					+ (props.resume() != null ? " resume=" + props.resume() : "") + " pause=" + props.pause());
			var s = service.run(new ExperimentService.Request(props.cases(), props.limit(), props.resume()));
			String text = report(s);
			if (props.compareWith() != null) {
				text += comparison(service.compare(props.compareWith(), s), s);
			}
			Path file = RunReports.write(props.reportDir(), "benchmark", text);
			log.info("\n{}", text);
			RunReports.banner(log, "BENCHMARK " + (s.status().equals("COMPLETED") ? "FINISHED: COMPLETED"
					: "STOPPED: " + s.status()), String.format("experiment=%d selected=%d executed now=%d already done=%d"
							+ "%nreport: %s%s", s.experimentId(), s.selected(), s.executed(), s.alreadyDone(),
							file.toAbsolutePath(), s.stopReason() == null ? "" : "\n" + s.stopReason()));
			code = s.status().equals("COMPLETED") ? 0 : 1;
		}
		catch (RuntimeException e) {
			RunReports.banner(log, "BENCHMARK FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(context, () -> exit));
	}

	static String report(ExperimentService.Summary s) {
		StringBuilder sb = new StringBuilder("===== BENCHMARK REPORT =====\n");
		sb.append(String.format("experiment=%d run=%d strategy=%s status=%s dataset=%s v%s (sha256 %s)%n",
				s.experimentId(), s.runId(), s.strategy(), s.status(), s.dataset().name(), s.dataset().version(),
				s.dataset().sha256().substring(0, 12)));
		sb.append(String.format("cases: selected=%d stored=%d (executed now=%d, already done=%d)%n", s.selected(),
				s.results().size(), s.executed(), s.alreadyDone()));
		if (s.stopReason() != null) {
			sb.append("STOP: ").append(s.stopReason()).append('\n');
		}
		sb.append("Note: no LLM judge; content metrics are deterministic (must_contain / must_not_contain).\n");
		sb.append("Note: TOOLS_REQUIRED cases (STRUCTURED) need tools that only the Agentic strategy has (M8).\n");

		Map<String, List<Aggregate>> bySlice = s.aggregates().stream().collect(Collectors.groupingBy(
				a -> a.sliceType() + "=" + a.sliceValue(), LinkedHashMap::new, Collectors.toList()));
		for (var e : bySlice.entrySet()) {
			sb.append("\n--- ").append(e.getKey()).append(" ---\n");
			Map<String, Aggregate> byMetric = e.getValue().stream()
					.collect(Collectors.toMap(Aggregate::metric, a -> a, (a, b) -> a, LinkedHashMap::new));
			for (String m : HEADLINE) {
				Aggregate a = byMetric.get(m);
				if (a != null) {
					String v = m.endsWith("_ms") || m.endsWith("_total") ? String.format("%,.0f", a.value())
							: String.format("%.3f", a.value());
					sb.append(String.format("  %-28s %12s  (n=%d)%n", m, v, a.sampleSize()));
				}
			}
		}
		sb.append("\n--- per question ---\n");
		sb.append(String.format("  %-14s %-14s %-6s %-10s %-5s %-7s%n", "case", "category", "lang", "status", "pass",
				"recall"));
		for (var r : s.results()) {
			Double recall = r.metrics().get("recall_at_k");
			sb.append(String.format("  %-14s %-14s %-6s %-10s %-5s %-7s%n", r.caseKey(), r.category(), r.language(),
					r.answerStatus(), r.metrics().get("case_pass") == 1.0 ? "yes" : "NO",
					recall == null ? "-" : String.format("%.2f", recall)));
		}
		return sb.toString();
	}

	/** Metrics compared side by side (a subset of HEADLINE that is meaningful as a delta). */
	static final List<String> COMPARED = List.of("case_pass", "behavior_correct", "must_contain_pass", "language_match",
			"citation_precision", "recall_at_k", "precision_at_k", "mrr", "ndcg_at_k", "hit_at_k", "security_pass",
			"latency_p50_ms", "latency_p95_ms", "embedding_calls_total", "chat_calls_total", "prompt_tokens_total");

	static String comparison(ExperimentService.Comparison c, ExperimentService.Summary s) {
		String a = c.baselineStrategy() + "#" + c.baselineExperimentId();
		String b = s.strategy() + "#" + s.experimentId();
		StringBuilder sb = new StringBuilder("\n===== COMPARISON: " + a + " vs " + b + " =====\n");
		sb.append("common cases: ").append(c.commonCases().size()).append(" (metrics recomputed on these only)\n");
		for (String slice : List.of("ALL=ALL", "SCOPE=RAG_ONLY")) {
			sb.append("\n--- ").append(slice).append(" ---\n");
			sb.append(String.format("  %-24s %12s %12s %10s%n", "metric", a, b, "delta"));
			for (String m : COMPARED) {
				Aggregate x = find(c.baseline(), m, slice);
				Aggregate y = find(c.current(), m, slice);
				if (x == null && y == null) {
					continue;
				}
				boolean whole = m.endsWith("_ms") || m.endsWith("_total");
				sb.append(String.format("  %-24s %12s %12s %10s%n", m, fmt(x, whole), fmt(y, whole),
						x == null || y == null ? "-" : String.format(whole ? "%+,.0f" : "%+.3f", y.value() - x.value())));
			}
		}
		sb.append("\n--- per question (pass / recall) ---\n");
		Map<String, ExperimentStore.ResultRow> base = new LinkedHashMap<>();
		c.baselineRows().forEach(r -> base.put(r.caseKey(), r));
		for (var r : c.currentRows()) {
			var x = base.get(r.caseKey());
			String change = x.metrics().get("case_pass").equals(r.metrics().get("case_pass")) ? ""
					: r.metrics().get("case_pass") == 1.0 ? "  <-- fixed" : "  <-- regressed";
			sb.append(String.format("  %-14s %-10s %-5s %-5s | %-10s %-5s %-5s%s%n", r.caseKey(), x.answerStatus(),
					pass(x), recall(x), r.answerStatus(), pass(r), recall(r), change));
		}
		return sb.toString();
	}

	private static Aggregate find(List<Aggregate> list, String metric, String slice) {
		return list.stream().filter(a -> a.metric().equals(metric) && (a.sliceType() + "=" + a.sliceValue()).equals(slice))
				.findFirst().orElse(null);
	}

	private static String fmt(Aggregate a, boolean whole) {
		return a == null ? "-" : String.format(whole ? "%,.0f" : "%.3f", a.value());
	}

	private static String pass(ExperimentStore.ResultRow r) {
		return r.metrics().get("case_pass") == 1.0 ? "yes" : "NO";
	}

	private static String recall(ExperimentStore.ResultRow r) {
		Double v = r.metrics().get("recall_at_k");
		return v == null ? "-" : String.format("%.2f", v);
	}
}
