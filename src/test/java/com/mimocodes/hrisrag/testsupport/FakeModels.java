package com.mimocodes.hrisrag.testsupport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Deterministic stand-ins for the Gemini models so integration tests never call the API. Import
 * {@link FakeModels.Config} on a test class; all importing classes share one Spring context (and one container).
 */
public final class FakeModels {

	private FakeModels() {
	}

	/** Feature-hashing bag of words: texts sharing words get similar vectors, so similarity search is meaningful. */
	public static final class HashingEmbeddingModel implements EmbeddingModel {
		public static final AtomicInteger REQUESTS = new AtomicInteger();
		private static final Pattern WORD = Pattern.compile("\\p{L}+");

		public static float[] vector(String text) {
			float[] v = new float[768];
			Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
			while (m.find()) {
				v[Math.floorMod(m.group().hashCode(), 768)] += 1;
			}
			double norm = 0;
			for (float x : v) {
				norm += x * x;
			}
			if (norm == 0) {
				v[0] = 1;
				return v;
			}
			for (int i = 0; i < v.length; i++) {
				v[i] /= (float) Math.sqrt(norm);
			}
			return v;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			REQUESTS.incrementAndGet();
			List<String> texts = request.getInstructions();
			return new EmbeddingResponse(IntStream.range(0, texts.size())
					.mapToObj(i -> new Embedding(vector(texts.get(i)), i)).toList());
		}

		@Override
		public float[] embed(Document document) {
			return vector(document.getText());
		}

		@Override
		public int dimensions() {
			return 768;
		}
	}

	/** Chat model whose reply is set per test; every prompt is recorded. */
	public static final class ScriptedChatModel implements ChatModel {
		public static final AtomicReference<Function<Prompt, String>> RESPONDER =
				new AtomicReference<>(p -> "Default answer [S1].");
		public static final List<Prompt> PROMPTS = Collections.synchronizedList(new ArrayList<>());

		@Override
		public ChatResponse call(Prompt prompt) {
			PROMPTS.add(prompt);
			String text = RESPONDER.get().apply(prompt);
			return new ChatResponse(
					List.of(new Generation(new AssistantMessage(text),
							ChatGenerationMetadata.builder().finishReason("STOP").build())),
					ChatResponseMetadata.builder().usage(new DefaultUsage(120, 30, 150)).build());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Config {
		@Bean
		@Primary
		EmbeddingModel fakeEmbeddingModel() {
			return new HashingEmbeddingModel();
		}

		@Bean
		@Primary
		ChatModel fakeChatModel() {
			return new ScriptedChatModel();
		}
	}
}
