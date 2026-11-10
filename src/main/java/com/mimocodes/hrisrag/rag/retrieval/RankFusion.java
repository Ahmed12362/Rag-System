package com.mimocodes.hrisrag.rag.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;

/**
 * Reciprocal Rank Fusion (Cormack et al., 2009): score(d) = Σ 1 / (k + rank_i(d)) over the lists that contain d.
 * Uses ranks only, so cosine similarity and ts_rank_cd need no calibration against each other. Ties are broken by
 * chunk id, so the result is deterministic.
 */
public final class RankFusion {

	private RankFusion() {
	}

	public static List<RetrievedChunk> rrf(List<RetrievedChunk> vector, List<RetrievedChunk> keyword, int k, int topK) {
		Map<Long, RetrievedChunk> chunks = new LinkedHashMap<>();
		Map<Long, Double> score = new LinkedHashMap<>();
		Map<Long, Integer> vRank = new LinkedHashMap<>();
		Map<Long, Integer> kRank = new LinkedHashMap<>();
		for (RetrievedChunk c : vector) {
			chunks.putIfAbsent(c.chunkId(), c);
			vRank.putIfAbsent(c.chunkId(), c.rank());
			score.merge(c.chunkId(), 1.0 / (k + c.rank()), Double::sum);
		}
		for (RetrievedChunk c : keyword) {
			chunks.putIfAbsent(c.chunkId(), c);
			kRank.putIfAbsent(c.chunkId(), c.rank());
			score.merge(c.chunkId(), 1.0 / (k + c.rank()), Double::sum);
		}
		List<Long> ids = new ArrayList<>(score.keySet());
		ids.sort(Comparator.comparingDouble((Long id) -> score.get(id)).reversed().thenComparingLong(id -> id));
		List<RetrievedChunk> out = new ArrayList<>();
		for (int i = 0; i < Math.min(topK, ids.size()); i++) {
			long id = ids.get(i);
			out.add(chunks.get(id).ranked(score.get(id), i + 1, vRank.get(id), kRank.get(id)));
		}
		return out;
	}
}
