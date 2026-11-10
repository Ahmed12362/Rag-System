package com.mimocodes.hrisrag.rag.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.ai.GenerationService;
import com.mimocodes.hrisrag.document.corpus.TextChecks;
import com.mimocodes.hrisrag.rag.citation.CitationValidator;
import com.mimocodes.hrisrag.rag.core.RagContract.AnswerStatus;
import com.mimocodes.hrisrag.rag.core.RagContract.GuardrailDecision;
import com.mimocodes.hrisrag.rag.core.RagContract.ModelCall;
import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.RagSettings;
import com.mimocodes.hrisrag.rag.core.RagContract.RagStrategy;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagContract.Timings;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.prompt.ContextBuilder;
import com.mimocodes.hrisrag.rag.prompt.GroundedPrompt;
import com.mimocodes.hrisrag.rag.retrieval.QueryEmbedder;
import com.mimocodes.hrisrag.rag.retrieval.VectorRetriever;

/**
 * Strategy A, the baseline: embed the question → vector top-K (security filters in SQL) → numbered context →
 * grounded generation → citation validation. Abstains without calling the LLM when nothing is retrievable, and when
 * the model reports insufficient context. Infrastructure failures become status ERROR with a safe message.
 */
@Component
public class NaiveRagStrategy implements RagStrategy {

	private static final Logger log = LoggerFactory.getLogger(NaiveRagStrategy.class);

	static final String ABSTAIN_EN = "I could not find this in the HR policies available to you. Please contact HR through the HR helpdesk.";
	static final String ABSTAIN_AR = "لم أجد إجابة لهذا السؤال في سياسات الموارد البشرية المتاحة لك. يرجى التواصل مع الموارد البشرية عبر مكتب المساعدة.";
	static final String ERROR_EN = "The assistant is temporarily unavailable. Please try again later.";
	static final String ERROR_AR = "المساعد غير متاح مؤقتاً. يرجى المحاولة لاحقاً.";

	private final QueryEmbedder embedder;
	private final VectorRetriever retriever;
	private final ContextBuilder contextBuilder;
	private final GenerationService generation;
	private final CitationValidator citations;
	private final RagProperties props;

	public NaiveRagStrategy(QueryEmbedder embedder, VectorRetriever retriever, ContextBuilder contextBuilder,
			GenerationService generation, CitationValidator citations, RagProperties props) {
		this.embedder = embedder;
		this.retriever = retriever;
		this.contextBuilder = contextBuilder;
		this.generation = generation;
		this.citations = citations;
		this.props = props;
	}

	@Override
	public StrategyId id() {
		return StrategyId.NAIVE;
	}

	@Override
	public RagResult execute(RagQuery query, RagSettings settings) {
		long t0 = System.nanoTime();
		UUID requestId = UUID.randomUUID();
		boolean arabic = TextChecks.arabicRatio(query.question()) > 0.3;
		List<ModelCall> calls = new ArrayList<>();
		List<GuardrailDecision> guardrails = new ArrayList<>();
		long embeddingMs = 0, retrievalMs = 0, generationMs = 0;

		// Input guardrail (the API validates too; strategies are also called directly by the benchmark).
		String question = query.question() == null ? "" : query.question().strip();
		if (question.isEmpty() || question.length() > props.maxQuestionChars()) {
			guardrails.add(new GuardrailDecision("INPUT", "LENGTH", "BLOCK",
					"question length " + question.length() + " outside 1.." + props.maxQuestionChars()));
			return result(requestId, AnswerStatus.BLOCKED, arabic ? ABSTAIN_AR : ABSTAIN_EN, List.of(), List.of(), "",
					calls, guardrails, t0, 0, 0, 0, settings);
		}
		guardrails.add(new GuardrailDecision("INPUT", "LENGTH", "PASS", null));

		try {
			long t = System.nanoTime();
			QueryEmbedder.QueryVector qv = embedder.embed(question);
			embeddingMs = ms(t);
			calls.add(qv.call());

			t = System.nanoTime();
			List<RetrievedChunk> retrieved = retriever.retrieve(qv.vector(), query.user(), settings.topK());
			retrievalMs = ms(t);
			guardrails.add(new GuardrailDecision("RETRIEVAL", "ACTIVE_ACCESS_SCOPE_FILTER", "APPLIED",
					"status=ACTIVE, access=" + query.user().visibleAccessLevels() + ", department="
							+ query.user().department()));
			if (retrieved.isEmpty()) {
				guardrails.add(new GuardrailDecision("GENERATION", "NO_EVIDENCE", "ABSTAIN", "no retrievable chunks"));
				return result(requestId, AnswerStatus.ABSTAINED, arabic ? ABSTAIN_AR : ABSTAIN_EN, List.of(), retrieved, "",
						calls, guardrails, t0, embeddingMs, retrievalMs, 0, settings);
			}

			ContextBuilder.Context context = contextBuilder.build(retrieved, settings.maxContextTokens());
			t = System.nanoTime();
			GenerationService.Generation g = generation.generate(GroundedPrompt.SYSTEM,
					GroundedPrompt.user(question, context.text()), settings.chatModel(), settings.temperature(),
					props.generationTimeout());
			generationMs = ms(t);
			calls.add(new ModelCall("GENERATE", g.model(), g.latencyMs(), g.promptTokens(), g.completionTokens(),
					g.thinkingTokens(), g.finishReason(), false));

			if (g.text().contains(GroundedPrompt.INSUFFICIENT)) {
				guardrails.add(new GuardrailDecision("GENERATION", "INSUFFICIENT_CONTEXT", "ABSTAIN",
						"model reported insufficient evidence"));
				return result(requestId, AnswerStatus.ABSTAINED, arabic ? ABSTAIN_AR : ABSTAIN_EN, List.of(), retrieved,
						context.text(), calls, guardrails, t0, embeddingMs, retrievalMs, generationMs, settings);
			}
			CitationValidator.Result cited = citations.validate(g.text(), context.sources());
			guardrails.add(new GuardrailDecision("OUTPUT", "CITATIONS",
					cited.invalidSourceIds().isEmpty() && !cited.uncited() ? "PASS" : "FLAG",
					cited.uncited() ? "answer has no valid citation"
							: cited.invalidSourceIds().isEmpty() ? null : "removed invalid " + cited.invalidSourceIds()));
			return result(requestId, AnswerStatus.ANSWERED, cited.answer(), cited.citations(), retrieved, context.text(),
					calls, guardrails, t0, embeddingMs, retrievalMs, generationMs, settings);
		}
		catch (RuntimeException e) {
			// Full provider/infrastructure details go to the server log only. The response (and trace) get a fixed,
			// safe description: raw provider errors can contain project ids, URLs and configuration details.
			log.error("request {} failed: {}", requestId, e.getMessage(), e);
			guardrails.add(new GuardrailDecision("SYSTEM", "PROVIDER_OR_INFRASTRUCTURE", "ERROR",
					"upstream model or infrastructure error (details in server logs, request " + requestId + ")"));
			return result(requestId, AnswerStatus.ERROR, arabic ? ERROR_AR : ERROR_EN, List.of(), List.of(), "", calls,
					guardrails, t0, embeddingMs, retrievalMs, generationMs, settings);
		}
	}

	private RagResult result(UUID id, AnswerStatus status, String answer, List<com.mimocodes.hrisrag.rag.core.RagContract.Citation> cites,
			List<RetrievedChunk> retrieved, String context, List<ModelCall> calls, List<GuardrailDecision> guardrails,
			long t0, long embeddingMs, long retrievalMs, long generationMs, RagSettings settings) {
		return new RagResult(id, StrategyId.NAIVE, status, answer, cites, retrieved, context, List.copyOf(calls),
				List.copyOf(guardrails), new Timings(ms(t0), embeddingMs, retrievalMs, generationMs), settings,
				GroundedPrompt.VERSION);
	}

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
