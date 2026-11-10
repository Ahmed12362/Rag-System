package com.mimocodes.hrisrag.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.embedding.*}: the embedding model used for BOTH documents and queries (vectors from different models
 * are not comparable, so changing the model requires a full re-embed).
 *
 * @param batchSize        texts per provider request (Gemini embeds a list of texts in one call)
 * @param delayMs          pause between batch requests (pacing for per-minute limits)
 * @param maxRetries       retries for transient 429/5xx errors, exponential backoff from initialBackoffMs
 */
@ConfigurationProperties(prefix = "hrisrag.embedding")
public record EmbeddingProperties(
		@DefaultValue("gemini-embedding-001") String model,
		@DefaultValue("768") int dimensions,
		@DefaultValue("25") int batchSize,
		@DefaultValue("500") long delayMs,
		@DefaultValue("4") int maxRetries,
		@DefaultValue("5000") long initialBackoffMs) {
}
