package com.mimocodes.hrisrag.rag.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.ai.EmbeddingService;
import com.mimocodes.hrisrag.ai.GenerationService;
import com.mimocodes.hrisrag.ai.ProviderErrors;
import com.mimocodes.hrisrag.document.corpus.TextChecks;
import com.mimocodes.hrisrag.rag.citation.CitationValidator;
import com.mimocodes.hrisrag.rag.core.RagContract.AnswerStatus;
import com.mimocodes.hrisrag.rag.core.RagContract.CallCounts;
import com.mimocodes.hrisrag.rag.core.RagContract.Citation;
import com.mimocodes.hrisrag.rag.core.RagContract.GuardrailDecision;
import com.mimocodes.hrisrag.rag.core.RagContract.ModelCall;
import com.mimocodes.hrisrag.rag.core.RagContract.PassageCheck;
import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.RagSettings;
import com.mimocodes.hrisrag.rag.core.RagContract.RagStrategy;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagContract.Timings;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.guardrail.AccessPolicy;
import com.mimocodes.hrisrag.rag.guardrail.GuardrailProperties;
import com.mimocodes.hrisrag.rag.guardrail.GuardrailProperties.Mode;
import com.mimocodes.hrisrag.rag.guardrail.GuardrailProperties.ScreeningMode;
import com.mimocodes.hrisrag.rag.guardrail.InjectionDetector;
import com.mimocodes.hrisrag.rag.guardrail.OutputGuard;
import com.mimocodes.hrisrag.rag.prompt.ContextBuilder;
import com.mimocodes.hrisrag.rag.prompt.GroundedPrompt;
import com.mimocodes.hrisrag.rag.retrieval.QueryEmbedder;
import com.mimocodes.hrisrag.rag.retrieval.VectorRetriever;

/**
 * Strategy A, the baseline, with layered guardrails:
 * <ol>
 * <li>INPUT: length, then direct prompt injection. A blocked question makes no embedding and no chat call.</li>
 * <li>RETRIEVAL: vector top-K with the access filters inside the SQL (primary access control).</li>
 * <li>CONTEXT: every chunk re-checked by {@link AccessPolicy} (second access check), then screened for indirect
 * injection. Only chunks that pass reach the context. {@code retrieved} keeps the raw retrieval for metrics;
 * {@code passageChecks} records what happened to each chunk and why.</li>
 * <li>GENERATION: grounded prompt; sources are wrapped as untrusted data.</li>
 * <li>OUTPUT: abstention marker, citation validation, then the system-prompt / secret leak check.</li>
 * </ol>
 * Infrastructure failures become status ERROR with a safe message.
 */
@Component
public class NaiveRagStrategy implements RagStrategy {

	private static final Logger log = LoggerFactory.getLogger(NaiveRagStrategy.class);

	static final String ABSTAIN_EN = "I could not find this in the HR policies available to you. Please contact HR through the HR helpdesk.";
	static final String ABSTAIN_AR = "لم أجد إجابة لهذا السؤال في سياسات الموارد البشرية المتاحة لك. يرجى التواصل مع الموارد البشرية عبر مكتب المساعدة.";
	static final String BLOCKED_EN = "I can only answer questions about the Company's HR policies, and I can't help with this request.";
	static final String BLOCKED_AR = "يمكنني فقط الإجابة عن أسئلة سياسات الموارد البشرية في الشركة، ولا يمكنني المساعدة في هذا الطلب.";
	static final String ERROR_EN = "The assistant is temporarily unavailable. Please try again later.";
	static final String ERROR_AR = "المساعد غير متاح مؤقتاً. يرجى المحاولة لاحقاً.";

	private final QueryEmbedder embedder;
	private final VectorRetriever retriever;
	private final ContextBuilder contextBuilder;
	private final GenerationService generation;
	private final CitationValidator citations;
	private final AccessPolicy accessPolicy;
	private final InjectionDetector injection;
	private final OutputGuard outputGuard;
	private final GuardrailProperties guardrailProps;
	private final RagProperties props;

	public NaiveRagStrategy(QueryEmbedder embedder, VectorRetriever retriever, ContextBuilder contextBuilder,
			GenerationService generation, CitationValidator citations, AccessPolicy accessPolicy,
			InjectionDetector injection, OutputGuard outputGuard, GuardrailProperties guardrailProps, RagProperties props) {
		this.embedder = embedder;
		this.retriever = retriever;
		this.contextBuilder = contextBuilder;
		this.generation = generation;
		this.citations = citations;
		this.accessPolicy = accessPolicy;
		this.injection = injection;
		this.outputGuard = outputGuard;
		this.guardrailProps = guardrailProps;
		this.props = props;
	}

	@Override
	public StrategyId id() {
		return StrategyId.NAIVE;
	}

	/** Mutable state of one request; turned into an immutable {@link RagResult} at the end. */
	private final class Run {
		final long t0 = System.nanoTime();
		final UUID requestId = UUID.randomUUID();
		final RagSettings settings;
		final boolean arabic;
		final List<ModelCall> calls = new ArrayList<>();
		final List<GuardrailDecision> guardrails = new ArrayList<>();
		List<RetrievedChunk> retrieved = List.of();
		final List<PassageCheck> passageChecks = new ArrayList<>();
		String context = "";
		long embeddingMs, retrievalMs, generationMs;

		Run(RagQuery query, RagSettings settings) {
			this.settings = settings;
			this.arabic = TextChecks.arabicRatio(query.question() == null ? "" : query.question()) > 0.3;
		}

		void guard(String layer, String rule, String outcome, String detail) {
			guardrails.add(new GuardrailDecision(layer, rule, outcome, detail));
		}

		RagResult finish(AnswerStatus status, String answer, List<Citation> cites) {
			return new RagResult(requestId, StrategyId.NAIVE, status, answer, cites, retrieved,
					List.copyOf(passageChecks), context, List.copyOf(calls), CallCounts.of(calls), List.copyOf(guardrails),
					new Timings(ms(t0), embeddingMs, retrievalMs, generationMs), settings, GroundedPrompt.VERSION);
		}

		RagResult abstain() {
			return finish(AnswerStatus.ABSTAINED, arabic ? ABSTAIN_AR : ABSTAIN_EN, List.of());
		}

		RagResult block() {
			return finish(AnswerStatus.BLOCKED, arabic ? BLOCKED_AR : BLOCKED_EN, List.of());
		}
	}

	@Override
	public RagResult execute(RagQuery query, RagSettings settings) {
		Run run = new Run(query, settings);

		// 1. INPUT (the API validates length too; strategies are also called directly by the benchmark).
		String question = query.question() == null ? "" : query.question().strip();
		if (question.isEmpty() || question.length() > props.maxQuestionChars()) {
			run.guard("INPUT", "LENGTH", "BLOCK",
					"question length " + question.length() + " outside 1.." + props.maxQuestionChars());
			return run.block();
		}
		run.guard("INPUT", "LENGTH", "PASS", null);
		if (guardrailProps.inputInjection() != Mode.OFF) {
			List<String> hits = injection.scanInput(question);
			if (hits.isEmpty()) {
				run.guard("INPUT", "PROMPT_INJECTION", "PASS", null);
			}
			else if (guardrailProps.inputInjection() == Mode.BLOCK) {
				run.guard("INPUT", "PROMPT_INJECTION", "BLOCK", "matched " + hits);
				return run.block(); // no embedding, no retrieval, no chat call
			}
			else {
				run.guard("INPUT", "PROMPT_INJECTION", "FLAG", "matched " + hits);
			}
		}

		try {
			// 2. RETRIEVAL (access filters inside the SQL)
			long t = System.nanoTime();
			QueryEmbedder.QueryVector qv = embedder.embed(question);
			run.embeddingMs = ms(t);
			run.calls.add(qv.call());

			t = System.nanoTime();
			run.retrieved = retriever.retrieve(qv.vector(), query.user(), settings.topK());
			run.retrievalMs = ms(t);
			run.guard("RETRIEVAL", "ACTIVE_ACCESS_SCOPE_FILTER", "APPLIED", "status=ACTIVE, access<="
					+ query.user().maxAccessLevel() + ", department=" + query.user().department());

			// 3. CONTEXT: access re-check, then indirect-injection screening, BEFORE anything is sent to the model
			List<RetrievedChunk> allowed = screen(run, query);
			if (allowed.isEmpty()) {
				run.guard("GENERATION", "NO_EVIDENCE", "ABSTAIN",
						run.retrieved.isEmpty() ? "no retrievable chunks" : "no chunk passed the context guardrails");
				return run.abstain();
			}
			ContextBuilder.Context context = contextBuilder.build(allowed, settings.maxContextTokens());
			run.context = context.text();

			// 4. GENERATION
			t = System.nanoTime();
			GenerationService.Generation g = generation.generate(GroundedPrompt.SYSTEM,
					GroundedPrompt.user(question, context.text()), settings.chatModel(), settings.temperature(),
					props.generationTimeout());
			run.generationMs = ms(t);
			run.calls.add(new ModelCall("GENERATE", g.model(), g.latencyMs(), g.promptTokens(), g.completionTokens(),
					g.thinkingTokens(), g.finishReason(), false));

			// 5. OUTPUT
			if (g.text().contains(GroundedPrompt.INSUFFICIENT)) {
				run.guard("GENERATION", "INSUFFICIENT_CONTEXT", "ABSTAIN", "model reported insufficient evidence");
				return run.abstain();
			}
			CitationValidator.Result cited = citations.validate(g.text(), context.sources());
			run.guard("OUTPUT", "CITATIONS", cited.invalidSourceIds().isEmpty() && !cited.uncited() ? "PASS" : "FLAG",
					cited.uncited() ? "answer has no valid citation"
							: cited.invalidSourceIds().isEmpty() ? null : "removed invalid " + cited.invalidSourceIds());
			if (guardrailProps.outputCheck()) {
				Optional<String> leak = outputGuard.check(cited.answer());
				if (leak.isPresent()) {
					// The leaked text is neither returned nor stored (the trace keeps the rule id only).
					log.warn("request {} answer blocked by output guard: {}", run.requestId, leak.get());
					run.guard("OUTPUT", leak.get(), "BLOCK", "answer withheld");
					return run.block();
				}
				run.guard("OUTPUT", "LEAK_CHECK", "PASS", null);
			}
			return run.finish(AnswerStatus.ANSWERED, cited.answer(), cited.citations());
		}
		catch (RuntimeException e) {
			// Full provider/infrastructure details go to the server log only. The response (and trace) get a fixed,
			// safe description: raw provider errors can contain project ids, URLs and configuration details.
			log.error("request {} failed: {}", run.requestId, e.getMessage(), e);
			// Quota exhaustion gets its own rule id (still a safe, fixed text) so callers such as the benchmark can stop
			// and resume later instead of scoring an infrastructure problem as a wrong answer.
			boolean quota = e instanceof EmbeddingService.EmbeddingQuotaExceededException || ProviderErrors.isRateLimit(e);
			run.guard("SYSTEM", quota ? "PROVIDER_QUOTA" : "PROVIDER_OR_INFRASTRUCTURE", "ERROR",
					(quota ? "model provider quota or rate limit reached" : "upstream model or infrastructure error")
							+ " (details in server logs, request " + run.requestId + ")");
			run.context = "";
			return run.finish(AnswerStatus.ERROR, run.arabic ? ERROR_AR : ERROR_EN, List.of());
		}
	}

	/** Access re-check and injection screening per chunk; returns the chunks (possibly redacted) that may be sent. */
	private List<RetrievedChunk> screen(Run run, RagQuery query) {
		List<RetrievedChunk> allowed = new ArrayList<>();
		int denied = 0, flagged = 0;
		ScreeningMode mode = guardrailProps.contextScreening();
		for (RetrievedChunk c : run.retrieved) {
			Optional<String> denial = accessPolicy.denialReason(query.user(), c);
			if (denial.isPresent()) {
				// Should never happen (the SQL filters first). Logged loudly because it means a retriever bug.
				log.error("request {}: chunk {} ({}) failed the access re-check: {}", run.requestId, c.chunkId(),
						c.docKey(), denial.get());
				run.passageChecks.add(new PassageCheck(c.chunkId(), c.docKey(), c.rank(), "DENIED",
						List.of(denial.get()), 0));
				denied++;
				continue;
			}
			if (mode == ScreeningMode.OFF) {
				run.passageChecks.add(new PassageCheck(c.chunkId(), c.docKey(), c.rank(), "CLEAN", List.of(), 0));
				allowed.add(c);
				continue;
			}
			InjectionDetector.Screening s = injection.screenPassage(c.content());
			if (!s.flagged()) {
				run.passageChecks.add(new PassageCheck(c.chunkId(), c.docKey(), c.rank(), "CLEAN", List.of(), 0));
				allowed.add(c);
			}
			else if (mode == ScreeningMode.FLAG) {
				flagged++;
				run.passageChecks.add(new PassageCheck(c.chunkId(), c.docKey(), c.rank(), "FLAGGED", s.rules(), 0));
				allowed.add(c);
			}
			else if (s.nothingLeft() || s.cleaned().isBlank()) {
				flagged++;
				run.passageChecks.add(new PassageCheck(c.chunkId(), c.docKey(), c.rank(), "REMOVED", s.rules(),
						s.removedSentences()));
			}
			else {
				flagged++;
				run.passageChecks.add(new PassageCheck(c.chunkId(), c.docKey(), c.rank(), "REDACTED", s.rules(),
						s.removedSentences()));
				allowed.add(c.withContent(s.cleaned()));
			}
		}
		run.guard("CONTEXT", "ACCESS_RECHECK", denied == 0 ? "PASS" : "BLOCK",
				denied == 0 ? null : denied + " chunk(s) denied before context construction");
		if (mode != ScreeningMode.OFF) {
			run.guard("CONTEXT", "INDIRECT_INJECTION", flagged == 0 ? "PASS" : mode == ScreeningMode.REDACT ? "REDACT" : "FLAG",
					flagged == 0 ? null : flagged + " passage(s) contained instruction-like text (see passageChecks)");
		}
		return allowed;
	}

	private static long ms(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000;
	}
}
