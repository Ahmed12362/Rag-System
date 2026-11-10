package com.mimocodes.hrisrag.rag.strategy;

import java.util.List;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.ai.GenerationService;
import com.mimocodes.hrisrag.rag.citation.CitationValidator;
import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagSettings;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.guardrail.AccessPolicy;
import com.mimocodes.hrisrag.rag.guardrail.GuardrailProperties;
import com.mimocodes.hrisrag.rag.guardrail.InjectionDetector;
import com.mimocodes.hrisrag.rag.guardrail.OutputGuard;
import com.mimocodes.hrisrag.rag.prompt.ContextBuilder;
import com.mimocodes.hrisrag.rag.retrieval.KeywordRetriever;
import com.mimocodes.hrisrag.rag.retrieval.QueryEmbedder;
import com.mimocodes.hrisrag.rag.retrieval.RankFusion;
import com.mimocodes.hrisrag.rag.retrieval.VectorRetriever;

/**
 * Strategy B: vector top-N and keyword (full-text) top-N, fused with Reciprocal Rank Fusion, then the top-K. Uses the
 * same single query embedding as Naive (so the same query cache) and the same {@link GroundedRagStrategy} pipeline;
 * only retrieval differs. Each returned chunk carries its vector and keyword ranks.
 */
@Component
public class HybridRagStrategy extends GroundedRagStrategy {

	private final QueryEmbedder embedder;
	private final VectorRetriever vector;
	private final KeywordRetriever keyword;

	public HybridRagStrategy(QueryEmbedder embedder, VectorRetriever vector, KeywordRetriever keyword,
			ContextBuilder contextBuilder, GenerationService generation, CitationValidator citations,
			AccessPolicy accessPolicy, InjectionDetector injection, OutputGuard outputGuard,
			GuardrailProperties guardrailProps, RagProperties props) {
		super(contextBuilder, generation, citations, accessPolicy, injection, outputGuard, guardrailProps, props);
		this.embedder = embedder;
		this.vector = vector;
		this.keyword = keyword;
	}

	@Override
	public StrategyId id() {
		return StrategyId.HYBRID;
	}

	@Override
	protected List<RetrievedChunk> retrieve(Run run, String question, RagQuery query, RagSettings settings) {
		int pool = Math.max(settings.topK(), props.hybrid().candidates());
		long t = System.nanoTime();
		QueryEmbedder.QueryVector qv = embedder.embed(question);
		run.embeddingMs = ms(t);
		run.calls.add(qv.call());
		t = System.nanoTime();
		List<RetrievedChunk> byVector = vector.retrieve(qv.vector(), query.user(), pool);
		List<RetrievedChunk> byKeyword = keyword.retrieve(question, query.user(), pool);
		List<RetrievedChunk> fused = RankFusion.rrf(byVector, byKeyword, props.hybrid().rrfK(), settings.topK());
		run.retrievalMs = ms(t);
		run.guard("RETRIEVAL", "HYBRID_FUSION", "APPLIED", "rrf k=" + props.hybrid().rrfK() + ", candidates vector="
				+ byVector.size() + " keyword=" + byKeyword.size() + ", fused top-" + settings.topK());
		return fused;
	}
}
