package com.mimocodes.hrisrag.evaluation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Document-level retrieval metrics (docs/benchmark-methodology.md). The ranking is the list of retrieved CHUNKS' doc keys
 * in rank order; it is first reduced to distinct documents (first occurrence wins), because several chunks of one
 * document must not count as several hits. {@code k} is the number of retrieved chunks considered (the strategy's top-K).
 * Relevance is graded: 2 = authoritative, 1 = supporting.
 */
public final class RetrievalMetrics {

	private RetrievalMetrics() {
	}

	/** Distinct documents among the first {@code k} chunks, in rank order. */
	public static List<String> distinctDocs(List<String> chunkDocKeys, int k) {
		return new ArrayList<>(new LinkedHashSet<>(chunkDocKeys.subList(0, Math.min(k, chunkDocKeys.size()))));
	}

	/** Share of relevant documents found in the top-k. */
	public static double recall(List<String> docs, Map<String, Integer> relevant) {
		if (relevant.isEmpty()) {
			throw new IllegalArgumentException("recall needs at least one relevant document");
		}
		return docs.stream().filter(relevant::containsKey).count() / (double) relevant.size();
	}

	/** Share of the distinct retrieved documents that are relevant (0 when nothing was retrieved). */
	public static double precision(List<String> docs, Map<String, Integer> relevant) {
		return docs.isEmpty() ? 0 : docs.stream().filter(relevant::containsKey).count() / (double) docs.size();
	}

	/** 1 / rank of the first relevant document, 0 if none. */
	public static double reciprocalRank(List<String> docs, Map<String, Integer> relevant) {
		for (int i = 0; i < docs.size(); i++) {
			if (relevant.containsKey(docs.get(i))) {
				return 1.0 / (i + 1);
			}
		}
		return 0;
	}

	public static double hit(List<String> docs, Map<String, Integer> relevant) {
		return docs.stream().anyMatch(relevant::containsKey) ? 1 : 0;
	}

	/**
	 * NDCG with gain 2^grade − 1 and log2 discount. The ideal ranking places the highest grades first, truncated to the
	 * same number of positions as the actual distinct-document list could hold ({@code k}).
	 */
	public static double ndcg(List<String> docs, Map<String, Integer> relevant, int k) {
		double dcg = 0;
		for (int i = 0; i < docs.size(); i++) {
			dcg += gain(relevant.getOrDefault(docs.get(i), 0)) / log2(i + 2);
		}
		List<Integer> ideal = relevant.values().stream().sorted((a, b) -> b - a).limit(k).toList();
		double idcg = 0;
		for (int i = 0; i < ideal.size(); i++) {
			idcg += gain(ideal.get(i)) / log2(i + 2);
		}
		return idcg == 0 ? 0 : dcg / idcg;
	}

	private static double gain(int grade) {
		return Math.pow(2, grade) - 1;
	}

	private static double log2(double x) {
		return Math.log(x) / Math.log(2);
	}
}
