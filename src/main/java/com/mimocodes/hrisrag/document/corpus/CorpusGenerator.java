package com.mimocodes.hrisrag.document.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.mimocodes.hrisrag.config.ChatOptionsFactory;

/**
 * Generates corpus documents with the configured chat model (through Spring AI's provider-neutral ChatModel).
 * <ul>
 * <li>Resume: a document whose file already validates is skipped (no API call).</li>
 * <li>Transient provider errors (429/5xx, "unavailable", "overloaded") are retried with exponential backoff.</li>
 * <li>A draft that fails validation is regenerated with the validator's messages as feedback.</li>
 * <li>Only validated documents are written to the corpus; rejected drafts go to a separate folder for inspection.</li>
 * </ul>
 */
public class CorpusGenerator {

	private static final Logger log = LoggerFactory.getLogger(CorpusGenerator.class);

	/** Abstracted so tests do not really wait. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis);
	}

	public enum Status { SKIPPED_VALID, GENERATED, FAILED }

	public record Outcome(PlannedDocument plan, Status status, int apiCalls, List<String> lastErrors, Integer promptTokens,
			Integer completionTokens, Integer thinkingTokens, long latencyMs) {
	}

	private record Draft(String body, Integer promptTokens, Integer completionTokens, Integer thinkingTokens,
			long latencyMs, int calls) {
	}

	private final ChatModel chatModel;
	private final ChatOptionsFactory optionsFactory;
	private final CorpusProperties props;
	private final PromptBuilder prompts;
	private final CorpusDocumentValidator validator;
	private final Sleeper sleeper;

	public CorpusGenerator(ChatModel chatModel, ChatOptionsFactory optionsFactory, CorpusProperties props,
			PromptBuilder prompts, CorpusDocumentValidator validator, Sleeper sleeper) {
		this.chatModel = chatModel;
		this.optionsFactory = optionsFactory;
		this.props = props;
		this.prompts = prompts;
		this.validator = validator;
		this.sleeper = sleeper;
	}

	public List<Outcome> generate(CorpusSpec spec, List<PlannedDocument> selection, Path corpusRoot, Path rejectedRoot) {
		CorpusManifest manifest = CorpusManifest.load(corpusRoot);
		List<Outcome> outcomes = new ArrayList<>();
		int i = 0;
		for (PlannedDocument plan : selection) {
			i++;
			Outcome outcome = generateOne(spec, plan, corpusRoot, rejectedRoot, manifest);
			outcomes.add(outcome);
			log.info("[{}/{}] {} {} (api calls: {}){}", i, selection.size(), outcome.status(), plan.id(),
					outcome.apiCalls(), outcome.lastErrors().isEmpty() ? "" : " errors=" + outcome.lastErrors());
			manifest.save(); // persist progress after every document so an interrupted run can resume
		}
		return outcomes;
	}

	private Outcome generateOne(CorpusSpec spec, PlannedDocument plan, Path corpusRoot, Path rejectedRoot,
			CorpusManifest manifest) {
		Path target = corpusRoot.resolve(plan.relativePath());
		if (Files.exists(target) && validator.validateFile(plan, read(target), spec.facts()).isEmpty()) {
			return new Outcome(plan, Status.SKIPPED_VALID, 0, List.of(), null, null, null, 0);
		}

		List<String> feedback = List.of();
		int calls = 0;
		Draft draft = null;
		for (int attempt = 0; attempt <= props.maxFeedbackAttempts(); attempt++) {
			try {
				draft = callWithRetry(plan, spec, feedback);
			}
			catch (RuntimeException e) {
				return new Outcome(plan, Status.FAILED, calls + 1, List.of("provider error: " + e.getMessage()), null,
						null, null, 0);
			}
			calls += draft.calls();
			String body = draft.body().strip() + "\n";
			feedback = validator.validateBody(plan, body, spec.facts());
			if (feedback.isEmpty()) {
				write(target, plan.metadata().toFrontMatter() + body);
				manifest.put(plan.relativePath(), new CorpusManifest.Entry(plan.metadata().docKey(),
						plan.metadata().version(), plan.metadata().language(), plan.group(), CorpusManifest.sha256(target),
						props.model(), draft.promptTokens(), draft.completionTokens(), draft.thinkingTokens(),
						draft.latencyMs(), calls));
				return new Outcome(plan, Status.GENERATED, calls, List.of(), draft.promptTokens(),
						draft.completionTokens(), draft.thinkingTokens(), draft.latencyMs());
			}
			write(rejectedRoot.resolve(plan.relativePath() + ".attempt" + (attempt + 1)), body);
		}
		return new Outcome(plan, Status.FAILED, calls, feedback, draft.promptTokens(), draft.completionTokens(),
				draft.thinkingTokens(), draft.latencyMs());
	}

	private Draft callWithRetry(PlannedDocument plan, CorpusSpec spec, List<String> feedback) {
		Prompt prompt = new Prompt(
				List.of(new SystemMessage(PromptBuilder.SYSTEM_PROMPT),
						new UserMessage(prompts.userPrompt(plan, spec.facts(), feedback))),
				optionsFactory.create(props.model(), props.temperature()));
		long backoff = props.initialBackoffMs();
		for (int attempt = 1;; attempt++) {
			sleeper.sleep(props.delayMs()); // pacing between provider calls
			long start = System.nanoTime();
			try {
				ChatResponse response = chatModel.call(prompt);
				long latency = (System.nanoTime() - start) / 1_000_000;
				String text = response.getResult().getOutput().getText();
				Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
				Integer promptTokens = usage == null ? null : usage.getPromptTokens();
				Integer completion = usage == null ? null : usage.getCompletionTokens();
				Integer thinking = null;
				if (usage != null && usage.getTotalTokens() != null && promptTokens != null && completion != null) {
					int extra = usage.getTotalTokens() - promptTokens - completion;
					thinking = extra > 0 ? extra : 0;
				}
				return new Draft(text == null ? "" : text, promptTokens, completion, thinking, latency, attempt);
			}
			catch (RuntimeException e) {
				if (!isTransient(e) || attempt > props.maxTransientRetries()) {
					throw e;
				}
				log.warn("{}: transient provider error (attempt {}), retrying in {} ms: {}", plan.id(), attempt, backoff,
						e.getMessage());
				sleeper.sleep(backoff);
				backoff *= 2;
			}
		}
	}

	/** 429 / 5xx style failures that are worth retrying. Walks the cause chain. */
	static boolean isTransient(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String m = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
			if (m.contains("429") || m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504")
					|| m.contains("unavailable") || m.contains("overloaded") || m.contains("resource_exhausted")
					|| m.contains("high demand") || m.contains("timeout") || m.contains("timed out")) {
				return true;
			}
		}
		return false;
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void write(Path file, String content) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, content, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
