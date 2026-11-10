package com.mimocodes.hrisrag.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

class GenerationServiceTest {

	@Test
	void joinsAllResultPartsInOrder() {
		// Regression (Task 5.1 real run): Gemini returned "INSUFFICIENT_CONTEXT" split into parts; only "INSU" was read.
		ChatModel split = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("INSU")),
				new Generation(new AssistantMessage("FFICIENT_")), new Generation(new AssistantMessage("CONTEXT"))));
		var service = new GenerationService(split, (m, t) -> ChatOptions.builder().model(m).temperature(t).build());

		var g = service.generate("sys", "user", "m", 0.0, Duration.ofSeconds(5));

		assertThat(g.text()).isEqualTo("INSUFFICIENT_CONTEXT");
	}
}
