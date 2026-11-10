package com.mimocodes.hrisrag.document.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.common.RunReports;
import com.mimocodes.hrisrag.config.ChatOptionsFactory;

/**
 * Command-line entry point, active only with {@code --hrisrag.corpus.generate=true} or
 * {@code --hrisrag.corpus.validate-only=true}. Prints a report and exits (0 = success, 1 = failures).
 */
@Component
@ConditionalOnExpression("${hrisrag.corpus.generate:false} or ${hrisrag.corpus.validate-only:false}")
public class CorpusRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(CorpusRunner.class);

	private final CorpusProperties props;
	private final ChatModel chatModel;
	private final ChatOptionsFactory optionsFactory;
	private final PromptBuilder prompts;
	private final CorpusDocumentValidator validator;
	private final ConfigurableApplicationContext context;

	public CorpusRunner(CorpusProperties props, ChatModel chatModel, ChatOptionsFactory optionsFactory,
			PromptBuilder prompts, CorpusDocumentValidator validator, ConfigurableApplicationContext context) {
		this.props = props;
		this.chatModel = chatModel;
		this.optionsFactory = optionsFactory;
		this.prompts = prompts;
		this.validator = validator;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int code;
		try {
			CorpusSpec spec = CorpusSpec.load(Path.of(props.specDir()));
			List<String> specErrors = spec.validate();
			log.info("Corpus spec: {} facts, {} documents, by group {}, by language {}, spec errors {}",
					spec.facts().size(), spec.documents().size(), spec.countsByGroup(), spec.countsByLanguage(),
					specErrors.size());
			if (!specErrors.isEmpty()) {
				specErrors.forEach(e -> log.error("  spec: {}", e));
				code = 1;
			}
			else if (props.validateOnly()) {
				code = validateCorpus(spec) ? 0 : 1;
			}
			else {
				code = generate(spec) ? 0 : 1;
			}
		}
		catch (RuntimeException e) {
			log.error("Corpus run failed", e);
			code = 1;
		}
		int exit = code;
		System.exit(SpringApplication.exit(context, () -> exit));
	}

	private boolean generate(CorpusSpec spec) {
		List<PlannedDocument> selection = props.pilot() ? spec.pilotSelection()
				: props.only().isEmpty() ? spec.documents() : spec.select(props.only());
		banner("CORPUS GENERATION STARTED", selection.size() + " document(s) selected, model chain " + props.models()
				+ ", temperature " + props.temperature());
		CorpusGenerator generator = new CorpusGenerator(chatModel, optionsFactory, props, prompts, validator,
				CorpusRunner::sleep);
		List<CorpusGenerator.Outcome> outcomes = generator.generate(spec, selection, Path.of(props.outputDir()),
				Path.of(props.rejectedDir()));

		StringBuilder sb = new StringBuilder("\n===== CORPUS GENERATION REPORT =====\n");
		outcomes.forEach(o -> sb.append(String.format("%-14s %-48s %-12s %-24s calls=%d tokens(p/c/t)=%s/%s/%s %,d ms%n",
				o.status(), o.plan().id(), o.plan().group(), o.model(), o.apiCalls(), o.promptTokens(),
				o.completionTokens(), o.thinkingTokens(), o.latencyMs())));
		long failed = outcomes.stream().filter(o -> o.status() == CorpusGenerator.Status.FAILED).count();
		long notAttempted = outcomes.stream().filter(o -> o.status() == CorpusGenerator.Status.NOT_ATTEMPTED).count();
		long generated = outcomes.stream().filter(o -> o.status() == CorpusGenerator.Status.GENERATED).count();
		long skipped = outcomes.stream().filter(o -> o.status() == CorpusGenerator.Status.SKIPPED_VALID).count();
		sb.append(String.format("generated=%d skipped=%d failed=%d not_attempted=%d api_calls=%d prompt_tokens=%d "
				+ "completion_tokens=%d thinking_tokens=%d%n", generated, skipped, failed, notAttempted,
				outcomes.stream().mapToInt(CorpusGenerator.Outcome::apiCalls).sum(),
				sum(outcomes, CorpusGenerator.Outcome::promptTokens), sum(outcomes, CorpusGenerator.Outcome::completionTokens),
				sum(outcomes, CorpusGenerator.Outcome::thinkingTokens)));
		outcomes.stream().filter(o -> o.status() == CorpusGenerator.Status.FAILED)
				.forEach(o -> sb.append("FAILED ").append(o.plan().id()).append(": ").append(o.lastErrors()).append('\n'));
		if (notAttempted > 0) {
			sb.append("Quota exhausted for every model: re-run the same command later to resume.\n");
		}
		Path report = writeReport("generation", sb.toString());
		log.info(sb.toString());
		String result = notAttempted > 0 ? "STOPPED: QUOTA EXHAUSTED" : failed > 0 ? "FINISHED WITH FAILURES"
				: "FINISHED: SUCCESS";
		banner("CORPUS GENERATION " + result, String.format(
				"total=%d generated=%d skipped(valid)=%d failed=%d not_attempted=%d%nreport: %s", outcomes.size(),
				generated, skipped, failed, notAttempted, report.toAbsolutePath()));
		return failed == 0 && notAttempted == 0;
	}

	private static void banner(String title, String details) {
		RunReports.banner(log, title, details);
	}

	private Path writeReport(String kind, String content) {
		return RunReports.write(props.reportDir(), kind, content);
	}

	/** Validates every corpus file against the plan and the manifest checksums. */
	private boolean validateCorpus(CorpusSpec spec) {
		Path root = Path.of(props.outputDir());
		List<String> problems = new ArrayList<>(CorpusManifest.load(root).verifyChecksums());
		Set<String> planned = spec.documents().stream().map(PlannedDocument::relativePath).collect(Collectors.toSet());
		int checked = 0;
		for (PlannedDocument plan : spec.documents()) {
			Path file = root.resolve(plan.relativePath());
			if (!Files.exists(file)) {
				if (props.requireComplete()) {
					problems.add(plan.relativePath() + ": missing");
				}
				continue;
			}
			checked++;
			validator.validateFile(plan, read(file), spec.facts())
					.forEach(e -> problems.add(plan.relativePath() + ": " + e));
		}
		try (Stream<Path> files = Files.exists(root) ? Files.walk(root) : Stream.empty()) {
			files.filter(p -> p.toString().endsWith(".md"))
					.map(p -> root.relativize(p).toString().replace('\\', '/'))
					.filter(p -> !planned.contains(p)).forEach(p -> problems.add(p + ": not in the document plan"));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		StringBuilder sb = new StringBuilder("===== CORPUS VALIDATION REPORT =====\n");
		sb.append("planned=").append(spec.documents().size()).append(" checked=").append(checked).append(" problems=")
				.append(problems.size()).append(" require_complete=").append(props.requireComplete()).append('\n');
		problems.forEach(p -> sb.append(p).append('\n'));
		Path report = writeReport("validation", sb.toString());
		problems.forEach(p -> log.error("  {}", p));
		banner("CORPUS VALIDATION " + (problems.isEmpty() ? "PASSED" : "FAILED"), String.format(
				"planned=%d checked=%d problems=%d%nreport: %s", spec.documents().size(), checked, problems.size(),
				report.toAbsolutePath()));
		return problems.isEmpty();
	}

	private static long sum(List<CorpusGenerator.Outcome> outcomes,
			java.util.function.Function<CorpusGenerator.Outcome, Integer> f) {
		return outcomes.stream().map(f).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted", e);
		}
	}
}
