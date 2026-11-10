package com.mimocodes.hrisrag.benchmark;

import java.util.List;
import java.util.Map;

/**
 * One benchmark question with its labels. Labels are evaluation-only: they must never be passed to the answering
 * pipeline (architecture rule 3: rag must not depend on benchmark).
 *
 * @param language         en | ar | ar-eg | mixed
 * @param xling            cross-lingual type: EN_TO_AR | AR_TO_EN | EG_TO_MSA | MIXED (cross-lingual cases only)
 * @param targetLanguage   restricts derived relevant documents: en | ar | ar-msa (Arabic without Egyptian-dialect pages)
 * @param asker            employee code of the simulated user asking
 * @param aboutDepartment  department the question is about (department-scoped documents become relevant)
 * @param facts            fact ids the answer depends on; relevant documents are derived from them
 * @param mustContain      each item must appear in the answer; "a|b" means any alternative
 * @param expectedBehavior ANSWER | ABSTAIN | REFUSE
 * @param expectedAuth     ALLOWED | DENIED, or null when no data access is involved
 * @param docs             explicit relevant documents (doc key → grade), overriding the derivation
 * @param absentTerms      unanswerable cases: terms proven absent from the whole corpus
 */
public record BenchmarkCase(
		String id,
		String category,
		String language,
		String xling,
		String targetLanguage,
		String asker,
		String aboutDepartment,
		List<String> facts,
		String question,
		List<String> mustContain,
		List<String> mustNotContain,
		String expectedBehavior,
		String expectedAuth,
		String expectedTool,
		Map<String, String> toolArgs,
		List<String> forbiddenTools,
		Map<String, Integer> docs,
		List<String> absentTerms,
		String reference,
		String note) {

	public boolean answerable() {
		return "ANSWER".equals(expectedBehavior);
	}
}
