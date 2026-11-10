package com.mimocodes.hrisrag.rag.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.rag.*}. The caller identity is NOT configured here: it comes from the authenticated principal.
 *
 * @param maxContextTokens estimated-token budget for the sources sent to the model
 */
@ConfigurationProperties(prefix = "hrisrag.rag")
public record RagProperties(
		@DefaultValue("5") int topK,
		@DefaultValue("2500") int maxContextTokens,
		@DefaultValue("gemini-3.5-flash-lite") String chatModel,
		@DefaultValue("0.0") double temperature,
		@DefaultValue("2000") int maxQuestionChars,
		@DefaultValue("60s") Duration generationTimeout) {

	public RagContract.RagSettings defaultSettings() {
		return new RagContract.RagSettings(topK, maxContextTokens, chatModel, temperature);
	}
}
