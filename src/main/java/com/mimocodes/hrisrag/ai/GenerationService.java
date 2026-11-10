package com.mimocodes.hrisrag.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import com.mimocodes.hrisrag.config.ChatOptionsFactory;

/**
 * Thin gateway over Spring AI's {@link ChatModel} (decision D8): provider-specific options via
 * {@link ChatOptionsFactory}, a hard timeout, one retry for transient provider errors, and usage capture including
 * thinking tokens and the finish reason.
 */
@Service
public class GenerationService {

	private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

	/** Result of one call. thinkingTokens = total − prompt − completion (thinking models bill these separately). */
	public record Generation(String text, String model, long latencyMs, Integer promptTokens, Integer completionTokens,
			Integer thinkingTokens, String finishReason) {
	}

	public static class GenerationException extends RuntimeException {
		public GenerationException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private final ChatModel chatModel;
	private final ChatOptionsFactory optionsFactory;

	public GenerationService(ChatModel chatModel, ChatOptionsFactory optionsFactory) {
		this.chatModel = chatModel;
		this.optionsFactory = optionsFactory;
	}

	/**
	 * The full answer text. Gemini may return one candidate split into several parts, which Spring AI maps to several
	 * results; reading only {@code getResult()} returns the FIRST part (observed in Task 5.1: "INSU" instead of
	 * "INSUFFICIENT_CONTEXT", finishReason STOP). All parts are joined in order.
	 */
	public static String joinedText(ChatResponse response) {
		List<org.springframework.ai.chat.model.Generation> results = response.getResults();
		if (results.size() > 1) {
			log.info("provider returned {} result parts; joining them", results.size());
		}
		StringBuilder sb = new StringBuilder();
		for (var r : results) {
			String t = r.getOutput() == null ? null : r.getOutput().getText();
			if (t != null) {
				sb.append(t);
			}
		}
		return sb.toString();
	}

	public Generation generate(String systemPrompt, String userPrompt, String model, double temperature,
			Duration timeout) {
		Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)),
				optionsFactory.create(model, temperature));
		Call c = call(prompt, timeout);
		String text = joinedText(c.response());
		return new Generation(text == null ? "" : text, model, c.latencyMs(), c.promptTokens(), c.completionTokens(),
				c.thinkingTokens(), c.finishReason());
	}

	/** One model turn of a tool-using conversation. {@code message} keeps provider properties (thought signatures). */
	public record Turn(AssistantMessage message, String model, long latencyMs, Integer promptTokens,
			Integer completionTokens, Integer thinkingTokens, String finishReason) {
	}

	/**
	 * A conversation turn with declared tools. The tool callbacks are DECLARATIONS ONLY: Spring AI 2.0's ChatModel does
	 * not execute tools (it only builds function declarations), and these callbacks throw if anything ever tries, so
	 * every proposed call must come back to the caller's loop for validation and authorization (decision D7).
	 */
	public Turn converse(List<Message> messages, List<ToolDefinition> tools, String model, double temperature,
			Duration timeout) {
		List<ToolCallback> declarations = tools.stream().<ToolCallback>map(DeclarationOnly::new).toList();
		Call c = call(new Prompt(messages, optionsFactory.createWithTools(model, temperature, declarations)), timeout);
		return new Turn(joinedMessage(c.response()), model, c.latencyMs(), c.promptTokens(), c.completionTokens(),
				c.thinkingTokens(), c.finishReason());
	}

	/** All result parts as one assistant message: text joined, tool calls and provider properties combined in order. */
	static AssistantMessage joinedMessage(ChatResponse response) {
		List<org.springframework.ai.chat.model.Generation> results = response.getResults();
		if (results.size() == 1) {
			return results.getFirst().getOutput();
		}
		List<AssistantMessage.ToolCall> calls = new ArrayList<>();
		Map<String, Object> properties = new LinkedHashMap<>();
		for (var r : results) {
			if (r.getOutput() != null) {
				calls.addAll(r.getOutput().getToolCalls());
				properties.putAll(r.getOutput().getMetadata());
			}
		}
		return AssistantMessage.builder().content(joinedText(response)).toolCalls(calls).properties(properties).build();
	}

	private record DeclarationOnly(ToolDefinition definition) implements ToolCallback {
		@Override
		public ToolDefinition getToolDefinition() {
			return definition;
		}

		@Override
		public String call(String toolInput) {
			throw new IllegalStateException("tool " + definition.name() + " must be executed by the agent loop, not "
					+ "by the model client");
		}
	}

	private record Call(ChatResponse response, long latencyMs, Integer promptTokens, Integer completionTokens,
			Integer thinkingTokens, String finishReason) {
	}

	/** Timeout, one retry for transient errors, and usage capture, shared by single-shot and tool-using calls. */
	private Call call(Prompt prompt, Duration timeout) {
		for (int attempt = 1;; attempt++) {
			long start = System.nanoTime();
			try {
				ChatResponse response = CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
						.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				long latency = (System.nanoTime() - start) / 1_000_000;
				Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
				Integer p = usage == null ? null : usage.getPromptTokens();
				Integer c = usage == null ? null : usage.getCompletionTokens();
				Integer thinking = null;
				if (usage != null && usage.getTotalTokens() != null && p != null && c != null) {
					thinking = Math.max(0, usage.getTotalTokens() - p - c);
				}
				String finish = response.getResult() == null || response.getResult().getMetadata() == null ? null
						: response.getResult().getMetadata().getFinishReason();
				return new Call(response, latency, p, c, thinking, finish);
			}
			catch (TimeoutException e) {
				throw new GenerationException("generation timed out after " + timeout.toSeconds() + " s", e);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new GenerationException("interrupted", e);
			}
			catch (ExecutionException e) {
				Throwable cause = e.getCause() == null ? e : e.getCause();
				if (attempt == 1 && ProviderErrors.isTransient(cause) && !ProviderErrors.isDailyQuota(cause)) {
					log.warn("transient generation error, retrying once: {}", ProviderErrors.summary(cause));
					continue;
				}
				throw new GenerationException("generation failed: " + ProviderErrors.summary(cause), cause);
			}
		}
	}
}
