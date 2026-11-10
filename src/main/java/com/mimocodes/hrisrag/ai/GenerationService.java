package com.mimocodes.hrisrag.ai;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
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
		for (int attempt = 1;; attempt++) {
			long start = System.nanoTime();
			try {
				ChatResponse response = CompletableFuture.supplyAsync(() -> chatModel.call(prompt))
						.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
				long latency = (System.nanoTime() - start) / 1_000_000;
				String text = joinedText(response);
				Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
				Integer p = usage == null ? null : usage.getPromptTokens();
				Integer c = usage == null ? null : usage.getCompletionTokens();
				Integer thinking = null;
				if (usage != null && usage.getTotalTokens() != null && p != null && c != null) {
					thinking = Math.max(0, usage.getTotalTokens() - p - c);
				}
				String finish = response.getResult().getMetadata() == null ? null
						: response.getResult().getMetadata().getFinishReason();
				return new Generation(text == null ? "" : text, model, latency, p, c, thinking, finish);
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
