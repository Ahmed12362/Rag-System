package com.mimocodes.hrisrag.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.retrieval.KeywordRetrieverTestAccess;
import com.mimocodes.hrisrag.rag.retrieval.RankFusion;

class HybridUnitTest {

	static RetrievedChunk c(long id, int rank) {
		return new RetrievedChunk(id, "DOC-" + id, "1.0", "t", "s", "en", "ACTIVE", "INTERNAL", null, "x", 0.5, rank);
	}

	@Test
	void reciprocalRankFusionMatchesHandComputedScores() {
		// vector: A=10 (1), B=20 (2), C=30 (3); keyword: C=30 (1), D=5 (2)
		var vector = List.of(c(10, 1), c(20, 2), c(30, 3));
		var keyword = List.of(c(30, 1), c(5, 2));
		var fused = RankFusion.rrf(vector, keyword, 60, 3);

		// C = 1/63 + 1/61 = 0.032266; A = 1/61 = 0.016393; B = D = 1/62 = 0.016129 -> tie broken by chunk id (D=5 first)
		assertThat(fused).extracting(RetrievedChunk::chunkId).containsExactly(30L, 10L, 5L);
		assertThat(fused).extracting(RetrievedChunk::rank).containsExactly(1, 2, 3);
		assertThat(fused.get(0).score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-12));
		assertThat(fused.get(0).vectorRank()).isEqualTo(3);
		assertThat(fused.get(0).keywordRank()).isEqualTo(1);
		assertThat(fused.get(1).keywordRank()).isNull();                       // found by vector only
		assertThat(fused.get(2).vectorRank()).isNull();                        // found by keyword only
		assertThat(fused.get(2).keywordRank()).isEqualTo(2);

		assertThat(RankFusion.rrf(vector, List.of(), 60, 5)).extracting(RetrievedChunk::chunkId)
				.containsExactly(10L, 20L, 30L);                              // keyword empty: vector order kept
		assertThat(RankFusion.rrf(List.of(), List.of(), 60, 5)).isEmpty();
	}

	@Test
	void questionIsSplitByScriptForTheMatchingTextSearchConfiguration() {
		String[] parts = KeywordRetrieverTestAccess.split("How early must I request LR-1005 بطاقة الدخول؟ at 09:20");
		assertThat(parts[0]).isEqualTo("How early must I request LR-1005 at 09:20");
		assertThat(parts[1]).isEqualTo("بطاقة الدخول");
		assertThat(KeywordRetrieverTestAccess.split("?! ...")).containsExactly("", "");
	}
}
