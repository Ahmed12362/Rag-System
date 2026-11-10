package com.mimocodes.hrisrag.rag.retrieval;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.rag.core.RagContract.ModelCall;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;
import com.mimocodes.hrisrag.rag.core.RagProperties;

/**
 * Vector top-N + keyword top-N, fused with RRF, then the top-K. Used by the Hybrid strategy and by the Agentic
 * strategy's {@code search_policies} tool, so both retrieve identically. Both lists use {@link AccessSql}.
 */
@Component
public class HybridRetriever {

	public record Result(List<RetrievedChunk> chunks, ModelCall embeddingCall, int vectorCandidates,
			int keywordCandidates, long embeddingMs, long retrievalMs) {
	}

	private final QueryEmbedder embedder;
	private final VectorRetriever vector;
	private final KeywordRetriever keyword;
	private final RagProperties props;

	public HybridRetriever(QueryEmbedder embedder, VectorRetriever vector, KeywordRetriever keyword,
			RagProperties props) {
		this.embedder = embedder;
		this.vector = vector;
		this.keyword = keyword;
		this.props = props;
	}

	public Result retrieve(String query, UserContext user, int topK) {
		int pool = Math.max(topK, props.hybrid().candidates());
		long t = System.nanoTime();
		QueryEmbedder.QueryVector qv = embedder.embed(query);
		long embeddingMs = (System.nanoTime() - t) / 1_000_000;
		t = System.nanoTime();
		List<RetrievedChunk> byVector = vector.retrieve(qv.vector(), user, pool);
		List<RetrievedChunk> byKeyword = keyword.retrieve(query, user, pool);
		List<RetrievedChunk> fused = RankFusion.rrf(byVector, byKeyword, props.hybrid().rrfK(), topK);
		return new Result(fused, qv.call(), byVector.size(), byKeyword.size(), embeddingMs,
				(System.nanoTime() - t) / 1_000_000);
	}
}
