package com.mimocodes.hrisrag.config;

import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provider-specific wiring. Switching providers means replacing the beans here, not the callers. */
@Configuration(proxyBeanMethods = false)
public class ModelProviderConfiguration {

	@Bean
	ChatOptionsFactory chatOptionsFactory() {
		return (model, temperature) -> GoogleGenAiChatOptions.builder().model(model).temperature(temperature).build();
	}
}
