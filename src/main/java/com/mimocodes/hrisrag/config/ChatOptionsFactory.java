package com.mimocodes.hrisrag.config;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Creates per-call chat options for the configured provider.
 * <p>
 * Why this exists: Spring AI 2.0.1's Gemini ChatModel casts prompt options to its own GoogleGenAiChatOptions and
 * rejects the portable {@code ChatOptions.builder()} result (ClassCastException, observed in Task 3.2). Callers depend
 * on this interface; only the provider-specific implementation knows the concrete options class (decision D8).
 */
@FunctionalInterface
public interface ChatOptionsFactory {

	ChatOptions create(String model, double temperature);
}
