package com.mimocodes.hrisrag.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/** Regression test for the Task 3.2 pilot failure: Gemini's ChatModel requires its own options class. */
class ModelProviderConfigurationTest {

	@Test
	void factoryProducesGeminiOptionsCarryingModelAndTemperature() {
		var options = new ModelProviderConfiguration().chatOptionsFactory().create("gemini-3.5-flash-lite", 0.3);

		assertThat(options).isInstanceOf(GoogleGenAiChatOptions.class);
		assertThat(options.getModel()).isEqualTo("gemini-3.5-flash-lite");
		assertThat(options.getTemperature()).isEqualTo(0.3);
	}
}
