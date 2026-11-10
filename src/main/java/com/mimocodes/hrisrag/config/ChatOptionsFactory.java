package com.mimocodes.hrisrag.config;

import java.util.List;

import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

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

	/**
	 * Options that DECLARE tools to the model. In Spring AI 2.0 the ChatModel only turns the callbacks into function
	 * declarations; it does not execute them (verified in Task 8.1), so our agent loop runs every call itself.
	 */
	default ChatOptions createWithTools(String model, double temperature, List<ToolCallback> tools) {
		throw new UnsupportedOperationException("this provider configuration does not support tools");
	}
}
