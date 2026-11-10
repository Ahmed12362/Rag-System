package com.mimocodes.hrisrag.document.corpus;

import static com.mimocodes.hrisrag.document.corpus.CorpusTestFixtures.FACTS;
import static com.mimocodes.hrisrag.document.corpus.CorpusTestFixtures.englishBody;
import static com.mimocodes.hrisrag.document.corpus.CorpusTestFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/** Generator behaviour with a scripted fake ChatModel: no API calls, no real sleeping. */
class CorpusGeneratorTest {

	@TempDir
	Path tmp;

	private final List<Long> sleeps = new ArrayList<>();
	private final List<Prompt> prompts = new ArrayList<>();

	private static final String VALID = englishBody("Employees receive 21 working days and request 14 days ahead.");
	private static final String INVALID = englishBody("Employees receive 21 working days.");

	private CorpusProperties props() {
		return new CorpusProperties(true, false, false, List.of(), false, "corpus-spec", "corpus", "rejected",
				"test-model", 0.3, 10, 100, 3, 2);
	}

	private CorpusGenerator generator(Deque<Object> script) {
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			Object next = script.removeFirst();
			if (next instanceof RuntimeException e) {
				throw e;
			}
			return new ChatResponse(List.of(new Generation(new AssistantMessage((String) next))),
					ChatResponseMetadata.builder().usage(new DefaultUsage(100, 50, 180)).build());
		};
		return new CorpusGenerator(fake, (model, temperature) -> ChatOptions.builder().model(model)
				.temperature(temperature).build(), props(), new PromptBuilder(), new CorpusDocumentValidator(), sleeps::add);
	}

	private CorpusSpec spec(PlannedDocument p) {
		return new CorpusSpec(FACTS, List.of(p));
	}

	@Test
	void retriesTransientErrorsWithExponentialBackoffThenWritesFileAndManifest() throws Exception {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		var outcomes = generator(new ArrayDeque<>(List.of(new RuntimeException("503 UNAVAILABLE: high demand"),
				new RuntimeException("429 RESOURCE_EXHAUSTED"), VALID)))
				.generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("rejected"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.GENERATED);
		assertThat(outcomes.getFirst().apiCalls()).isEqualTo(3);
		assertThat(outcomes.getFirst().thinkingTokens()).isEqualTo(30); // 180 total - 100 prompt - 50 completion
		assertThat(sleeps).containsExactly(10L, 100L, 10L, 200L, 10L); // pacing, backoff 100, pacing, backoff 200, pacing
		Path file = tmp.resolve("corpus").resolve(p.relativePath());
		assertThat(Files.readString(file)).startsWith("---\ndoc_key: TEST-DOC-EN");
		CorpusManifest manifest = CorpusManifest.load(tmp.resolve("corpus"));
		assertThat(manifest.entries()).containsKey(p.relativePath());
		assertThat(manifest.verifyChecksums()).isEmpty();
	}

	@Test
	void invalidDraftIsRegeneratedWithValidatorFeedback() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		var outcomes = generator(new ArrayDeque<>(List.of(INVALID, VALID)))
				.generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("rejected"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.GENERATED);
		assertThat(prompts).hasSize(2);
		assertThat(prompts.get(1).getUserMessage().getText()).contains("failed validation").contains("[14]");
		assertThat(Files.exists(tmp.resolve("rejected").resolve(p.relativePath() + ".attempt1"))).isTrue();
	}

	@Test
	void givesUpAfterFeedbackAttemptsAndWritesNothingToTheCorpus() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		var outcomes = generator(new ArrayDeque<>(List.of(INVALID, INVALID, INVALID)))
				.generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("rejected"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.FAILED);
		assertThat(outcomes.getFirst().lastErrors()).anyMatch(e -> e.contains("[14]"));
		assertThat(Files.exists(tmp.resolve("corpus").resolve(p.relativePath()))).isFalse();
	}

	@Test
	void nonTransientErrorFailsImmediately() {
		var p = plan("en", List.of("DAYS"), null);
		var outcomes = generator(new ArrayDeque<>(List.of(new RuntimeException("400 INVALID_ARGUMENT"))))
				.generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("rejected"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.FAILED);
		assertThat(prompts).hasSize(1);
	}

	@Test
	void resumeSkipsDocumentsThatAlreadyValidate() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		generator(new ArrayDeque<>(List.of(VALID))).generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));
		prompts.clear();

		var outcomes = generator(new ArrayDeque<>()).generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.SKIPPED_VALID);
		assertThat(prompts).isEmpty();
	}

	@Test
	void manifestDetectsEditedFiles() throws Exception {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		generator(new ArrayDeque<>(List.of(VALID))).generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));
		Path file = tmp.resolve("corpus").resolve(p.relativePath());
		Files.writeString(file, Files.readString(file) + "\nedited\n");

		assertThat(CorpusManifest.load(tmp.resolve("corpus")).verifyChecksums()).anyMatch(e -> e.contains("checksum mismatch"));
	}

	@Test
	void transientDetectionWalksCauses() {
		assertThat(CorpusGenerator.isTransient(new RuntimeException("wrapper", new RuntimeException("HTTP 503")))).isTrue();
		assertThat(CorpusGenerator.isTransient(new RuntimeException("400 bad request"))).isFalse();
	}
}
