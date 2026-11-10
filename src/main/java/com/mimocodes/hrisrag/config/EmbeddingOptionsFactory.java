package com.mimocodes.hrisrag.config;

import org.springframework.ai.embedding.EmbeddingOptions;

/**
 * Creates per-call embedding options for the configured provider. Like {@link ChatOptionsFactory}, this exists because
 * the Gemini embedding model needs its own options class; callers stay provider-neutral (decision D8).
 */
@FunctionalInterface
public interface EmbeddingOptionsFactory {

	enum Purpose { DOCUMENT, QUERY }

	EmbeddingOptions create(String model, int dimensions, Purpose purpose);
}
