package com.mimocodes.hrisrag.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.mimocodes.hrisrag.benchmark.BenchmarkCase;
import com.mimocodes.hrisrag.evaluation.CaseScorer.Outcome;
import com.mimocodes.hrisrag.evaluation.MetricAggregator.Aggregate;
import com.mimocodes.hrisrag.evaluation.MetricAggregator.Scored;

/** Metric formulas checked against hand-computed values. */
class EvaluationUnitTest {

	// ranking of retrieved CHUNKS: A, A, B, C, D  → distinct documents A, B, C, D
	static final List<String> CHUNKS = List.of("A", "A", "B", "C", "D");
	static final Map<String, Integer> RELEVANT = Map.of("B", 2, "D", 1, "E", 2);

	@Test
	void retrievalMetricsMatchHandComputedValues() {
		List<String> docs = RetrievalMetrics.distinctDocs(CHUNKS, 5);
		assertThat(docs).containsExactly("A", "B", "C", "D");
		assertThat(RetrievalMetrics.recall(docs, RELEVANT)).isCloseTo(2.0 / 3, within(1e-9));   // B, D of {B, D, E}
		assertThat(RetrievalMetrics.precision(docs, RELEVANT)).isCloseTo(0.5, within(1e-9));    // 2 of 4 documents
		assertThat(RetrievalMetrics.reciprocalRank(docs, RELEVANT)).isCloseTo(0.5, within(1e-9)); // first hit at rank 2
		assertThat(RetrievalMetrics.hit(docs, RELEVANT)).isEqualTo(1.0);
		// DCG  = (2^2-1)/log2(3) + (2^1-1)/log2(5)              = 1.892789 + 0.430677 = 2.323466
		// IDCG = (2^2-1)/log2(2) + (2^2-1)/log2(3) + (2^1-1)/log2(4) = 3 + 1.892789 + 0.5 = 5.392789
		assertThat(RetrievalMetrics.ndcg(docs, RELEVANT, 5)).isCloseTo(2.323466 / 5.392789, within(1e-5));

		assertThat(RetrievalMetrics.distinctDocs(CHUNKS, 2)).containsExactly("A");  // k counts chunks
		assertThat(RetrievalMetrics.ndcg(List.of("B", "E", "D"), RELEVANT, 5)).isCloseTo(1.0, within(1e-9)); // ideal
		assertThat(RetrievalMetrics.precision(List.of(), RELEVANT)).isZero();
		assertThatThrownBy(() -> RetrievalMetrics.recall(List.of("A"), Map.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	static BenchmarkCase kase(String category, String language, String behavior, List<String> mustContain,
			List<String> mustNotContain) {
		return new BenchmarkCase("T-1", category, language, null, null, "E-1001", null, List.of(), "q?", mustContain,
				mustNotContain, behavior, null, null, Map.of(), List.of(), Map.of(), List.of(), null, null);
	}

	static Outcome outcome(String status, String answer, List<String> retrieved, List<String> cited) {
		return new Outcome(status, answer, retrieved, cited, 0, 0, 1200, 1, 0, 1, 1000, 40, 0);
	}

	@Test
	void answerMetricsHandleAlternativesArabicDigitsAndLanguage() {
		var ar = kase("FACTUAL", "ar", "ANSWER", List.of("14|fourteen"), List.of());
		var m = CaseScorer.score(ar, RELEVANT, outcome("ANSWERED", "يجب تقديم الطلب قبل ١٤ يوماً [S1].", CHUNKS,
				List.of("B", "X")), 5);
		assertThat(m).containsEntry("must_contain_pass", 1.0).containsEntry("language_match", 1.0)
				.containsEntry("behavior_correct", 1.0).containsEntry("case_pass", 1.0)
				.containsEntry("citation_precision", 0.5).containsEntry("answered_with_citation", 1.0)
				.containsEntry("must_not_contain_violation", null).containsEntry("security_pass", null);

		var englishAnswerToArabic = CaseScorer.score(ar, RELEVANT, outcome("ANSWERED", "At least 14 days [S1].",
				CHUNKS, List.of("B")), 5);
		assertThat(englishAnswerToArabic).containsEntry("language_match", 0.0).containsEntry("case_pass", 1.0);

		var missing = CaseScorer.score(ar, RELEVANT, outcome("ANSWERED", "قبل أسبوع [S1].", CHUNKS, List.of("B")), 5);
		assertThat(missing).containsEntry("must_contain_pass", 0.0).containsEntry("case_pass", 0.0);
	}

	@Test
	void behaviorAndLeakScoring() {
		var refuse = kase("SECURITY", "en", "REFUSE", List.of(), List.of("60 days"));
		assertThat(CaseScorer.score(refuse, Map.of(), outcome("BLOCKED", "I can't help.", List.of(), List.of()), 5))
				.containsEntry("behavior_correct", 1.0).containsEntry("security_pass", 1.0)
				.containsEntry("must_not_contain_violation", 0.0).containsEntry("recall_at_k", null)
				.containsEntry("language_match", null).containsEntry("status_blocked_rate", 1.0);

		var answerSec = kase("SECURITY", "en", "ANSWER", List.of(), List.of("60 days"));
		var leaked = CaseScorer.score(answerSec, Map.of("P", 2),
				outcome("ANSWERED", "Annual leave is 60 DAYS per year [S1].", List.of("P"), List.of("P")), 5);
		assertThat(leaked).containsEntry("must_not_contain_violation", 1.0).containsEntry("case_pass", 0.0)
				.containsEntry("security_pass", 0.0).containsEntry("recall_at_k", 1.0);

		var abstain = kase("UNANSWERABLE", "en", "ABSTAIN", List.of(), List.of());
		assertThat(CaseScorer.score(abstain, Map.of(), outcome("ANSWERED", "Ten weeks [S1].", List.of(), List.of("P")), 5))
				.containsEntry("behavior_correct", 0.0).containsEntry("must_contain_pass", null);

		assertThat(CaseScorer.scope(kase("STRUCTURED", "en", "ANSWER", List.of(), List.of()))).isEqualTo("TOOLS_REQUIRED");
		assertThat(CaseScorer.scope(abstain)).isEqualTo("RAG_ONLY");
	}

	@Test
	void aggregatesAverageOnlyApplicableCasesSumCountsAndUsePercentiles() {
		Map<String, Double> a = new LinkedHashMap<>();
		a.put("case_pass", 1.0);
		a.put("recall_at_k", 0.5);
		a.put("latency_ms", 100.0);
		a.put("chat_calls_count", 1.0);
		Map<String, Double> b = new LinkedHashMap<>(a);
		b.put("case_pass", 0.0);
		b.put("recall_at_k", null);         // not applicable: excluded from the mean and the sample size
		b.put("latency_ms", 300.0);
		Map<String, Double> c = new LinkedHashMap<>(a);
		c.put("latency_ms", 200.0);
		c.put("chat_calls_count", 0.0);
		var aggs = MetricAggregator.aggregate(List.of(new Scored("1", "FACTUAL", "en", "RAG_ONLY", a),
				new Scored("2", "FACTUAL", "ar", "RAG_ONLY", b), new Scored("3", "SECURITY", "en", "RAG_ONLY", c)));

		assertThat(find(aggs, "case_pass", "ALL")).satisfies(x -> {
			assertThat(x.value()).isCloseTo(2.0 / 3, within(1e-9));
			assertThat(x.sampleSize()).isEqualTo(3);
		});
		assertThat(find(aggs, "recall_at_k", "ALL")).satisfies(x -> {
			assertThat(x.value()).isEqualTo(0.5);
			assertThat(x.sampleSize()).isEqualTo(2);
		});
		assertThat(find(aggs, "chat_calls_total", "ALL").value()).isEqualTo(2.0);
		assertThat(find(aggs, "latency_p50_ms", "ALL").value()).isEqualTo(200.0);
		assertThat(find(aggs, "latency_p95_ms", "ALL").value()).isEqualTo(300.0);
		assertThat(aggs).anyMatch(x -> x.sliceType().equals("LANGUAGE") && x.sliceValue().equals("ar")
				&& x.metric().equals("case_pass") && x.value() == 0.0 && x.sampleSize() == 1);
		assertThat(aggs).noneMatch(x -> x.sliceValue().equals("ar") && x.metric().equals("recall_at_k"));

		assertThat(MetricAggregator.percentile(List.of(100.0, 200.0, 300.0, 400.0), 50)).isEqualTo(200.0);
		assertThat(MetricAggregator.percentile(List.of(100.0, 200.0, 300.0, 400.0), 95)).isEqualTo(400.0);
	}

	private static Aggregate find(List<Aggregate> aggs, String metric, String slice) {
		return aggs.stream().filter(x -> x.metric().equals(metric) && x.sliceType().equals(slice)).findFirst()
				.orElseThrow();
	}
}
