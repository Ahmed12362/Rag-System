package com.mimocodes.hrisrag.config;

import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provider-specific wiring. Switching providers means replacing the beans here, not the callers. */
@Configuration(proxyBeanMethods = false)
public class ModelProviderConfiguration {

	@Bean
	ChatOptionsFactory chatOptionsFactory() {
		return (model, temperature) -> GoogleGenAiChatOptions.builder().model(model).temperature(temperature).build();
	}

	/** Gemini embeddings distinguish document and query vectors (asymmetric retrieval task types). */
	@Bean
	EmbeddingOptionsFactory embeddingOptionsFactory() {
		return (model, dimensions, purpose) -> GoogleGenAiTextEmbeddingOptions.builder().model(model).dimensions(dimensions)
				.taskType(purpose == EmbeddingOptionsFactory.Purpose.QUERY ? TaskType.RETRIEVAL_QUERY
						: TaskType.RETRIEVAL_DOCUMENT)
				.build();
	}
}
