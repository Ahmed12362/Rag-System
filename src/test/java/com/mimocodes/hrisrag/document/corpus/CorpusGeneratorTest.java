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
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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

	private CorpusProperties props(List<String> models) {
		return new CorpusProperties(true, false, false, List.of(), false, "corpus-spec", "corpus", "rejected",
				"reports", models, 0.3, 10, 100, 3, 2);
	}

	private static ChatResponse response(String text) {
		return response(text, "STOP");
	}

	private static ChatResponse response(String text, String finishReason) {
		Generation generation = finishReason == null ? new Generation(new AssistantMessage(text))
				: new Generation(new AssistantMessage(text),
						ChatGenerationMetadata.builder().finishReason(finishReason).build());
		return new ChatResponse(List.of(generation),
				ChatResponseMetadata.builder().usage(new DefaultUsage(100, 50, 180)).build());
	}

	private CorpusGenerator generator(Deque<Object> script) {
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			Object next = script.removeFirst();
			if (next instanceof RuntimeException e) {
				throw e;
			}
			return response((String) next);
		};
		return generator(fake, List.of("test-model"));
	}

	private CorpusGenerator generator(ChatModel model, List<String> models) {
		return new CorpusGenerator(model, (m, temperature) -> ChatOptions.builder().model(m).temperature(temperature)
				.build(), props(models), new PromptBuilder(), new CorpusDocumentValidator(), sleeps::add);
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
	void dailyQuotaSwitchesToTheNextModelAndRecordsItInTheManifest() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			if ("model-a".equals(prompt.getOptions().getModel())) {
				throw new RuntimeException("429 RESOURCE_EXHAUSTED quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier");
			}
			return response(VALID);
		};
		var outcomes = generator(fake, List.of("model-a", "model-b"))
				.generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.GENERATED);
		assertThat(outcomes.getFirst().model()).isEqualTo("model-b");
		assertThat(prompts).extracting(pr -> pr.getOptions().getModel()).containsExactly("model-a", "model-b");
		assertThat(CorpusManifest.load(tmp.resolve("corpus")).entries().get(p.relativePath()).model()).isEqualTo("model-b");
	}

	@Test
	void persistent429TreatedAsExhaustedAfterRetries() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			if ("model-a".equals(prompt.getOptions().getModel())) {
				throw new RuntimeException("429 Too Many Requests");
			}
			return response(VALID);
		};
		var outcomes = generator(fake, List.of("model-a", "model-b"))
				.generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(outcomes.getFirst().model()).isEqualTo("model-b");
		assertThat(prompts.stream().filter(pr -> "model-a".equals(pr.getOptions().getModel()))).hasSize(4); // 1 + 3 retries
	}

	@Test
	void allModelsExhaustedStopsTheRunCleanly() {
		var p1 = plan("en", List.of("DAYS", "NOTICE"), null);
		var p2 = new PlannedDocument(new DocumentMetadata("TEST-DOC-2-EN", "1.0", "Test Policy", "LEAVE", "POLICY", "en",
				"ACTIVE", java.time.LocalDate.of(2026, 1, 1), null, null, "INTERNAL"), "CORE", List.of("DAYS"),
				p1.sections(), false, 40, 120, null, null, null, null, null);
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			throw new RuntimeException("429 quota exceeded: requests per day");
		};
		var outcomes = generator(fake, List.of("model-a", "model-b"))
				.generate(new CorpusSpec(FACTS, List.of(p1, p2)), List.of(p1, p2), tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(outcomes).extracting(CorpusGenerator.Outcome::status)
				.containsExactly(CorpusGenerator.Status.NOT_ATTEMPTED, CorpusGenerator.Status.NOT_ATTEMPTED);
		assertThat(prompts).hasSize(2); // one call per model, then stop: the second document is never sent
		assertThat(Files.exists(tmp.resolve("corpus").resolve(p1.relativePath()))).isFalse();
	}

	@Test
	void finishReasonsAreClassifiedWithoutAssumptions() {
		assertThat(CorpusGenerator.classify("STOP")).isEqualTo(CorpusGenerator.StopCategory.STOP);
		assertThat(CorpusGenerator.classify("RECITATION")).isEqualTo(CorpusGenerator.StopCategory.RECITATION);
		assertThat(CorpusGenerator.classify("SAFETY")).isEqualTo(CorpusGenerator.StopCategory.SAFETY);
		assertThat(CorpusGenerator.classify("PROHIBITED_CONTENT")).isEqualTo(CorpusGenerator.StopCategory.SAFETY);
		assertThat(CorpusGenerator.classify("MAX_TOKENS")).isEqualTo(CorpusGenerator.StopCategory.MAX_TOKENS);
		assertThat(CorpusGenerator.classify("MALFORMED_FUNCTION_CALL")).isEqualTo(CorpusGenerator.StopCategory.OTHER);
		assertThat(CorpusGenerator.classify(null)).isEqualTo(CorpusGenerator.StopCategory.UNKNOWN);
		assertThat(CorpusGenerator.StopCategory.UNKNOWN.blocksDraft()).isFalse();
		assertThat(CorpusGenerator.StopCategory.RECITATION.blocksDraft()).isTrue();
	}

	@Test
	void earlyProviderStopIsRejectedEvenWhenTheTextLooksValid() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			return response(VALID, "RECITATION");
		};
		var outcomes = generator(fake, List.of("m")).generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.FAILED);
		assertThat(outcomes.getFirst().lastErrors()).anyMatch(e -> e.contains("Provider stopped early: RECITATION"));
		assertThat(Files.exists(tmp.resolve("corpus").resolve(p.relativePath()))).isFalse();
	}

	@Test
	void missingFinishReasonDoesNotBlockAStructurallyValidDraft() {
		var p = plan("en", List.of("DAYS", "NOTICE"), null);
		ChatModel fake = prompt -> response(VALID, null);
		var outcomes = generator(fake, List.of("m")).generate(spec(p), List.of(p), tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(outcomes.getFirst().status()).isEqualTo(CorpusGenerator.Status.GENERATED);
	}

	@Test
	void perDocumentTemperatureAndVoiceApplyOnlyToThatDocument() {
		var normal = plan("en", List.of("DAYS", "NOTICE"), null);
		var adjusted = new PlannedDocument(new DocumentMetadata("TEST-DOC-2-EN", "1.0", "Test Policy", "LEAVE", "POLICY",
				"en", "ACTIVE", java.time.LocalDate.of(2026, 1, 1), null, null, "INTERNAL"), "CORE",
				List.of("DAYS", "NOTICE"), normal.sections(), false, 40, 120, null, null, null, 0.7,
				"Plain-spoken HR team voice with concrete Company-specific scenarios.");
		ChatModel fake = prompt -> {
			prompts.add(prompt);
			return response(VALID);
		};
		generator(fake, List.of("m")).generate(new CorpusSpec(FACTS, List.of(normal, adjusted)), List.of(normal, adjusted),
				tmp.resolve("corpus"), tmp.resolve("r"));

		assertThat(prompts).extracting(pr -> pr.getOptions().getTemperature()).containsExactly(0.3, 0.7);
		assertThat(prompts.get(0).getUserMessage().getText()).doesNotContain("Writing voice");
		assertThat(prompts.get(1).getUserMessage().getText()).contains("Writing voice (mandatory): Plain-spoken");
	}

	@Test
	void reRunNeverRewritesValidFilesOrDuplicatesDocuments() throws Exception {
		var a = plan("en", List.of("DAYS", "NOTICE"), null);
		var b = new PlannedDocument(new DocumentMetadata("TEST-DOC-2-EN", "1.0", "Test Policy", "LEAVE", "POLICY", "en",
				"ACTIVE", java.time.LocalDate.of(2026, 1, 1), null, null, "INTERNAL"), "CORE", List.of("DAYS", "NOTICE"),
				a.sections(), false, 40, 120, null, null, null, null, null);
		Path corpus = tmp.resolve("corpus");
		generator(new ArrayDeque<>(List.of(VALID))).generate(spec(a), List.of(a), corpus, tmp.resolve("r"));
		Path fileA = corpus.resolve(a.relativePath());
		byte[] bytesBefore = Files.readAllBytes(fileA);
		var modifiedBefore = Files.getLastModifiedTime(fileA);
		var entryBefore = CorpusManifest.load(corpus).entries().get(a.relativePath());
		prompts.clear();

		// Second run selects both: only B may be generated; A must be skipped untouched.
		generator(new ArrayDeque<>(List.of(VALID))).generate(new CorpusSpec(FACTS, List.of(a, b)), List.of(a, b), corpus,
				tmp.resolve("r"));

		assertThat(prompts).hasSize(1);
		assertThat(Files.readAllBytes(fileA)).isEqualTo(bytesBefore);
		assertThat(Files.getLastModifiedTime(fileA)).isEqualTo(modifiedBefore);
		assertThat(CorpusManifest.load(corpus).entries()).hasSize(2).containsEntry(a.relativePath(), entryBefore);
		try (var files = Files.walk(corpus)) {
			assertThat(files.filter(f -> f.toString().endsWith(".md")).count()).isEqualTo(2);
		}
	}

	@Test
	void transientDetectionWalksCauses() {
		assertThat(CorpusGenerator.isTransient(new RuntimeException("wrapper", new RuntimeException("HTTP 503")))).isTrue();
		assertThat(CorpusGenerator.isTransient(new RuntimeException("400 bad request"))).isFalse();
	}
}
