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
import com.mimocodes.hrisrag.rag.retrieval.QueryEmbedder;
import com.mimocodes.hrisrag.rag.retrieval.VectorRetriever;

/**
 * Strategy A, the baseline: embed the question and take the vector top-K. Everything else (guardrails, prompt,
 * citations, tracing) is the shared {@link GroundedRagStrategy} pipeline.
 */
@Component
public class NaiveRagStrategy extends GroundedRagStrategy {

	private final QueryEmbedder embedder;
	private final VectorRetriever retriever;

	public NaiveRagStrategy(QueryEmbedder embedder, VectorRetriever retriever, ContextBuilder contextBuilder,
			GenerationService generation, CitationValidator citations, AccessPolicy accessPolicy,
			InjectionDetector injection, OutputGuard outputGuard, GuardrailProperties guardrailProps, RagProperties props) {
		super(contextBuilder, generation, citations, accessPolicy, injection, outputGuard, guardrailProps, props);
		this.embedder = embedder;
		this.retriever = retriever;
	}

	@Override
	public StrategyId id() {
		return StrategyId.NAIVE;
	}

	@Override
	protected List<RetrievedChunk> retrieve(Run run, String question, RagQuery query, RagSettings settings) {
		long t = System.nanoTime();
		QueryEmbedder.QueryVector qv = embedder.embed(question);
		run.embeddingMs = ms(t);
		run.calls.add(qv.call());
		t = System.nanoTime();
		List<RetrievedChunk> chunks = retriever.retrieve(qv.vector(), query.user(), settings.topK());
		run.retrievalMs = ms(t);
		return chunks;
	}
}
