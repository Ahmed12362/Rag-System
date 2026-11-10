package com.mimocodes.hrisrag.rag.strategy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.ai.GenerationService;
import com.mimocodes.hrisrag.rag.citation.CitationValidator;
import com.mimocodes.hrisrag.rag.core.RagContract.AnswerStatus;
import com.mimocodes.hrisrag.rag.core.RagContract.ModelCall;
import com.mimocodes.hrisrag.rag.core.RagContract.RagQuery;
import com.mimocodes.hrisrag.rag.core.RagContract.RagResult;
import com.mimocodes.hrisrag.rag.core.RagContract.RagSettings;
import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.StrategyId;
import com.mimocodes.hrisrag.rag.core.RagContract.ToolCallTrace;
import com.mimocodes.hrisrag.rag.core.RagProperties;
import com.mimocodes.hrisrag.rag.guardrail.AccessPolicy;
import com.mimocodes.hrisrag.rag.guardrail.GuardrailProperties;
import com.mimocodes.hrisrag.rag.guardrail.InjectionDetector;
import com.mimocodes.hrisrag.rag.guardrail.OutputGuard;
import com.mimocodes.hrisrag.rag.prompt.AgentPrompt;
import com.mimocodes.hrisrag.rag.prompt.ContextBuilder;
import com.mimocodes.hrisrag.rag.retrieval.HybridRetriever;
import com.mimocodes.hrisrag.tools.HrisTools;
import com.mimocodes.hrisrag.tools.ToolsProperties;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Strategy C: a MANUAL, BOUNDED tool loop (decision D7). The model only proposes tool calls; this class validates,
 * authorizes (via {@link HrisTools}), executes, times and traces each one, then sends the results back. Limits: rounds,
 * total tool calls, a deadline. The shared {@link GroundedRagStrategy} pipeline still applies before (input guardrails)
 * and after (abstention, citations, leak check), and policy search uses the same retrieval and screening as Hybrid.
 * <p>
 * If any personal-data call was DENIED and none returned data, the answer is replaced by a fixed refusal (status
 * BLOCKED): the outcome does not depend on how the model words it.
 */
@Component
public class AgenticRagStrategy extends GroundedRagStrategy {

	public static final String SEARCH_POLICIES = "search_policies";

	static final String REFUSE_EN = "I can only share your own HR records (or your direct reports' records if you are "
			+ "their manager), so I can't share this information.";
	static final String REFUSE_AR = "يمكنني فقط مشاركة سجلاتك الخاصة في الموارد البشرية (أو سجلات من تديرهم مباشرة إذا كنت "
			+ "مديرهم)، لذلك لا يمكنني مشاركة هذه المعلومات.";
	static final String LIMIT_EN = "I couldn't complete this request within the allowed number of steps. Please rephrase "
			+ "the question or contact HR.";
	static final String LIMIT_AR = "لم أتمكن من إكمال هذا الطلب ضمن عدد الخطوات المسموح به. يرجى إعادة صياغة السؤال أو التواصل مع "
			+ "الموارد البشرية.";

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final ToolDefinition SEARCH = ToolDefinition.builder().name(SEARCH_POLICIES)
			.description("Search the Company's HR policy documents (policies, FAQs, procedures, handbook). Returns numbered "
					+ "sources to cite as [S#].")
			.inputSchema("{\"type\": \"object\", \"properties\": {\"query\": {\"type\": \"string\", \"description\": "
					+ "\"What to look for, in the user's language.\"}}, \"required\": [\"query\"], "
					+ "\"additionalProperties\": false}")
			.build();

	private final HybridRetriever retriever;
	private final ContextBuilder contextBuilder;
	private final GenerationService generation;
	private final InjectionDetector injection;
	private final HrisTools hrisTools;
	private final ToolsProperties toolsProps;

	public AgenticRagStrategy(HybridRetriever retriever, HrisTools hrisTools, ToolsProperties toolsProps,
			ContextBuilder contextBuilder, GenerationService generation, CitationValidator citations,
			AccessPolicy accessPolicy, InjectionDetector injection, OutputGuard outputGuard,
			GuardrailProperties guardrailProps, RagProperties props) {
		super(contextBuilder, generation, citations, accessPolicy, injection, outputGuard, guardrailProps, props);
		this.retriever = retriever;
		this.contextBuilder = contextBuilder;
		this.generation = generation;
		this.injection = injection;
		this.hrisTools = hrisTools;
		this.toolsProps = toolsProps;
	}

	@Override
	public StrategyId id() {
		return StrategyId.AGENTIC;
	}

	@Override
	protected String promptVersion() {
		return AgentPrompt.VERSION;
	}

	/** Conversation state shared by the policy-search tool calls of one request. */
	private static final class Evidence {
		final Map<String, RetrievedChunk> sources = new LinkedHashMap<>();
		final Set<Long> sentChunkIds = new LinkedHashSet<>();
		final List<RetrievedChunk> retrieved = new ArrayList<>();
		final StringBuilder context = new StringBuilder();
		int denied;
		int dataReturned;
	}

	@Override
	protected RagResult answer(Run run, String question, RagQuery query, RagSettings settings) {
		var agent = props.agent();
		List<ToolDefinition> tools = new ArrayList<>();
		tools.add(SEARCH);
		tools.addAll(hrisTools.definitions());
		List<Message> messages = new ArrayList<>(List.of(
				new SystemMessage(AgentPrompt.system(query.user(), toolsProps.referenceDate())), new UserMessage(question)));
		Evidence ev = new Evidence();
		long deadline = System.nanoTime() + agent.deadline().toNanos();
		int toolCalls = 0;

		for (int round = 1; round <= agent.maxIterations(); round++) {
			long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
			if (remainingMs <= 0) {
				return limit(run, ev, "deadline " + agent.deadline().toSeconds() + " s reached");
			}
			Duration timeout = Duration.ofMillis(Math.min(props.generationTimeout().toMillis(), remainingMs));
			long t = System.nanoTime();
			GenerationService.Turn turn = generation.converse(messages, tools, settings.chatModel(),
					settings.temperature(), timeout);
			run.generationMs += ms(t);
			run.calls.add(new ModelCall("GENERATE", turn.model(), turn.latencyMs(), turn.promptTokens(),
					turn.completionTokens(), turn.thinkingTokens(), turn.finishReason(), false));
			AssistantMessage msg = turn.message();

			if (!msg.hasToolCalls()) {
				return finalAnswer(run, ev, msg.getText() == null ? "" : msg.getText());
			}
			messages.add(msg); // unchanged: keeps provider properties such as Gemini thought signatures
			List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
			for (AssistantMessage.ToolCall call : msg.getToolCalls()) {
				long ts = System.nanoTime();
				String response;
				if (toolCalls >= agent.maxToolCalls()) {
					response = "{\"status\":\"LIMIT_REACHED\",\"message\":\"No more tool calls are allowed. Answer now.\"}";
					run.toolCalls.add(new ToolCallTrace(round, call.name(), Map.of(), "LIMIT",
							"max " + agent.maxToolCalls() + " tool calls", response.length(), ms(ts)));
				}
				else {
					toolCalls++;
					response = SEARCH_POLICIES.equals(call.name())
							? searchPolicies(run, query, settings, ev, round, call, ts)
							: personalData(run, query, ev, round, call, ts);
				}
				responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), response));
			}
			messages.add(ToolResponseMessage.builder().responses(responses).build());
		}
		return limit(run, ev, "max " + agent.maxIterations() + " model rounds reached");
	}

	private String personalData(Run run, RagQuery query, Evidence ev, int round, AssistantMessage.ToolCall call, long ts) {
		HrisTools.Execution e = hrisTools.execute(call.name(), call.arguments(), query.user());
		run.toolCalls.add(new ToolCallTrace(round, call.name(), e.arguments(), e.outcome(), e.reason(),
				e.responseJson().length(), ms(ts)));
		switch (e.outcome()) {
			case "DENIED" -> {
				ev.denied++;
				run.guard("TOOL", "AUTHORIZATION", "DENY", call.name() + " " + e.arguments() + ": " + e.reason());
			}
			case "INVALID" -> run.guard("TOOL", "VALIDATION", "REJECT", call.name() + ": " + e.reason());
			case "OK" -> ev.dataReturned++;
			default -> {
			}
		}
		return e.responseJson();
	}

	private String searchPolicies(Run run, RagQuery query, RagSettings settings, Evidence ev, int round,
			AssistantMessage.ToolCall call, long ts) {
		String q;
		try {
			Map<String, Object> args = call.arguments() == null || call.arguments().isBlank() ? Map.of()
					: JSON.readValue(call.arguments(), new TypeReference<Map<String, Object>>() {
					});
			q = args.get("query") instanceof String s ? s.strip() : "";
			if (q.isEmpty() || q.length() > props.maxQuestionChars() || args.size() != 1) {
				throw new IllegalArgumentException("query must be one non-empty string");
			}
		}
		catch (RuntimeException e) {
			String response = "{\"status\":\"INVALID_ARGUMENTS\",\"message\":\"search_policies needs {\\\"query\\\": text}\"}";
			run.toolCalls.add(new ToolCallTrace(round, SEARCH_POLICIES, Map.of(), "INVALID", "bad query argument",
					response.length(), ms(ts)));
			return response;
		}
		// A query written by the model is screened like a user question (it may echo injected text).
		List<String> hits = injection.scanInput(q);
		if (!hits.isEmpty()) {
			String response = "{\"status\":\"INVALID_ARGUMENTS\",\"message\":\"query rejected\"}";
			run.guard("TOOL", "PROMPT_INJECTION", "REJECT", SEARCH_POLICIES + " query matched " + hits);
			run.toolCalls.add(new ToolCallTrace(round, SEARCH_POLICIES, Map.of("query", q), "INVALID",
					"injection " + hits, response.length(), ms(ts)));
			return response;
		}
		HybridRetriever.Result r = retriever.retrieve(q, query.user(), settings.topK());
		run.calls.add(r.embeddingCall());
		run.embeddingMs += r.embeddingMs();
		run.retrievalMs += r.retrievalMs();
		List<RetrievedChunk> fresh = r.chunks().stream().filter(c -> !ev.sentChunkIds.contains(c.chunkId())).toList();
		ev.retrieved.addAll(fresh);
		List<RetrievedChunk> allowed = screen(run, query, fresh);
		ContextBuilder.Context ctx = contextBuilder.build(allowed, settings.maxContextTokens(), ev.sources.size() + 1);
		ev.sources.putAll(ctx.sources());
		ctx.sources().values().forEach(c -> ev.sentChunkIds.add(c.chunkId()));
		if (!ctx.text().isEmpty()) {
			ev.context.append(ev.context.isEmpty() ? "" : "\n\n").append(ctx.text());
		}
		String response = ctx.isEmpty()
				? "{\"status\":\"NO_RESULTS\",\"message\":\"No new policy text found for this query.\"}"
				: JSON.writeValueAsString(Map.of("status", "OK", "sources", ctx.text()));
		run.toolCalls.add(new ToolCallTrace(round, SEARCH_POLICIES, Map.of("query", q), "OK",
				ctx.sources().size() + " source(s)", response.length(), ms(ts)));
		return response;
	}

	private RagResult finalAnswer(Run run, Evidence ev, String text) {
		run.retrieved = List.copyOf(ev.retrieved);
		run.context = ev.context.toString();
		if (ev.denied > 0 && ev.dataReturned == 0) {
			// Deterministic refusal: no personal data was returned, whatever the model wrote.
			run.guard("TOOL", "AUTHORIZATION_OUTCOME", "REFUSE", ev.denied + " denied call(s), no data returned");
			return run.finish(AnswerStatus.BLOCKED, run.arabic ? REFUSE_AR : REFUSE_EN, List.of());
		}
		if (text.isBlank()) {
			run.guard("GENERATION", "EMPTY_ANSWER", "ABSTAIN", "the model returned no text");
			return run.abstain();
		}
		return checkAnswer(run, text, ev.sources);
	}

	private RagResult limit(Run run, Evidence ev, String detail) {
		run.retrieved = List.copyOf(ev.retrieved);
		run.context = ev.context.toString();
		run.guard("TOOL", "AGENT_LIMIT", "STOP", detail);
		return run.finish(AnswerStatus.ABSTAINED, run.arabic ? LIMIT_AR : LIMIT_EN, List.of());
	}
}
