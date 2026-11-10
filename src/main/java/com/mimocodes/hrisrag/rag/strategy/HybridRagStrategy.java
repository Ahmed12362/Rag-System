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
import com.mimocodes.hrisrag.rag.retrieval.HybridRetriever;

/**
 * Strategy B: vector top-N and keyword (full-text) top-N, fused with Reciprocal Rank Fusion, then the top-K
 * ({@link HybridRetriever}). Uses the same single query embedding as Naive (so the same query cache) and the same
 * {@link GroundedRagStrategy} pipeline; only retrieval differs. Each returned chunk carries its vector and keyword ranks.
 */
@Component
public class HybridRagStrategy extends GroundedRagStrategy {

	private final HybridRetriever hybrid;

	public HybridRagStrategy(HybridRetriever hybrid, ContextBuilder contextBuilder, GenerationService generation,
			CitationValidator citations, AccessPolicy accessPolicy, InjectionDetector injection, OutputGuard outputGuard,
			GuardrailProperties guardrailProps, RagProperties props) {
		super(contextBuilder, generation, citations, accessPolicy, injection, outputGuard, guardrailProps, props);
		this.hybrid = hybrid;
	}

	@Override
	public StrategyId id() {
		return StrategyId.HYBRID;
	}

	@Override
	protected List<RetrievedChunk> retrieve(Run run, String question, RagQuery query, RagSettings settings) {
		HybridRetriever.Result r = hybrid.retrieve(question, query.user(), settings.topK());
		run.embeddingMs = r.embeddingMs();
		run.retrievalMs = r.retrievalMs();
		run.calls.add(r.embeddingCall());
		run.guard("RETRIEVAL", "HYBRID_FUSION", "APPLIED", "rrf k=" + props.hybrid().rrfK() + ", candidates vector="
				+ r.vectorCandidates() + " keyword=" + r.keywordCandidates() + ", fused top-" + settings.topK());
		return r.chunks();
	}
}
