package com.mimocodes.hrisrag.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mimocodes.hrisrag.document.corpus.CorpusSpec;
import com.mimocodes.hrisrag.document.corpus.Fact;
import com.mimocodes.hrisrag.document.corpus.TextChecks;

/** Validates a benchmark dataset against the corpus plan and the committed corpus files. Empty list = valid. */
public final class BenchmarkDatasetValidator {

	static final Set<String> CATEGORIES = Set.of("FACTUAL", "MULTI_HOP", "CROSS_LINGUAL", "UNANSWERABLE", "SECURITY",
			"STRUCTURED");
	static final Set<String> LANGUAGES = Set.of("en", "ar", "ar-eg", "mixed");
	static final Set<String> BEHAVIORS = Set.of("ANSWER", "ABSTAIN", "REFUSE");
	/** A question sharing this many consecutive words with a corpus document counts as leaked. */
	static final int LEAKAGE_NGRAM = 8;

	private BenchmarkDatasetValidator() {
	}

	public static List<String> validate(BenchmarkDataset ds, CorpusSpec spec, Path corpusRoot) {
		List<String> errors = new ArrayList<>();
		List<String> corpus = corpusTexts(corpusRoot);
		Set<String> docKeys = spec.documents().stream().map(d -> d.metadata().docKey()).collect(Collectors.toSet());
		Set<String> activeKeys = spec.documents().stream().filter(d -> "ACTIVE".equals(d.metadata().status()))
				.map(d -> d.metadata().docKey()).collect(Collectors.toSet());

		Set<String> ids = new HashSet<>();
		for (BenchmarkCase c : ds.cases()) {
			String id = c.id();
			if (!ids.add(id)) {
				errors.add(id + ": duplicate id");
			}
			if (!CATEGORIES.contains(c.category())) {
				errors.add(id + ": unknown category " + c.category());
			}
			if (!LANGUAGES.contains(c.language())) {
				errors.add(id + ": unknown language " + c.language());
			}
			if (!BEHAVIORS.contains(c.expectedBehavior())) {
				errors.add(id + ": unknown expected_behavior " + c.expectedBehavior());
			}
			if (!ds.askers().containsKey(c.asker())) {
				errors.add(id + ": unknown asker " + c.asker());
			}
			if (c.question() == null || c.question().isBlank()) {
				errors.add(id + ": empty question");
			}
			c.facts().stream().filter(f -> !spec.facts().containsKey(f)).forEach(f -> errors.add(id + ": unknown fact " + f));
			c.docs().keySet().stream().filter(k -> !docKeys.contains(k)).forEach(k -> errors.add(id + ": unknown doc " + k));
			c.docs().keySet().stream().filter(k -> docKeys.contains(k) && !activeKeys.contains(k))
					.forEach(k -> errors.add(id + ": explicit relevant doc " + k + " is not ACTIVE"));

			Map<String, Integer> relevant = ds.relevantDocs(c, spec);
			if (c.answerable() && !c.facts().isEmpty() && relevant.isEmpty()) {
				errors.add(id + ": answerable case with facts but no derivable relevant document");
			}
			checkMustContainNumbers(c, spec.facts(), errors);
			checkCategory(c, ds, spec, relevant, corpus, errors);
			checkLeakage(c, corpus, errors);
		}
		ds.targets().forEach((cat, n) -> {
			long actual = ds.cases().stream().filter(c -> c.category().equals(cat)).count();
			if (actual != n) {
				errors.add("category " + cat + ": " + actual + " cases, target " + n);
			}
		});
		return errors;
	}

	private static void checkCategory(BenchmarkCase c, BenchmarkDataset ds, CorpusSpec spec,
			Map<String, Integer> relevant, List<String> corpus, List<String> errors) {
		String id = c.id();
		switch (c.category()) {
			case "UNANSWERABLE" -> {
				if (!"ABSTAIN".equals(c.expectedBehavior()) || !c.facts().isEmpty() || !c.docs().isEmpty()) {
					errors.add(id + ": unanswerable cases need ABSTAIN, no facts and no docs");
				}
				if (c.absentTerms().isEmpty()) {
					errors.add(id + ": unanswerable cases must list absent_terms");
				}
				for (String term : c.absentTerms()) {
					// Latin terms match whole words ("pet" must not hit "competent"); Arabic terms match as substrings
					// because Arabic attaches clitics (و، ال، ب) to the word.
					String t = TextChecks.squash(term.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " "));
					String needle = t.matches("[a-z0-9 ]+") ? " " + t + " " : t;
					if (corpus.stream().anyMatch(doc -> doc.contains(needle))) {
						errors.add(id + ": absent term '" + term + "' actually appears in the corpus");
					}
				}
			}
			case "STRUCTURED" -> {
				if (c.expectedTool() == null || !ds.tools().contains(c.expectedTool()) || c.toolArgs().isEmpty()) {
					errors.add(id + ": structured cases need an allowlisted expected_tool and tool_args");
				}
				if (c.mustContain().isEmpty()) {
					errors.add(id + ": structured cases need must_contain");
				}
			}
			case "CROSS_LINGUAL" -> {
				if (c.xling() == null) {
					errors.add(id + ": cross-lingual case without xling type");
				}
				if (!"MIXED".equals(c.xling())) {
					String q = c.language();
					String t = c.targetLanguage();
					boolean different = t != null && !(q.equals(t) || ("ar-msa".equals(t) && "ar".equals(q)));
					if (!different) {
						errors.add(id + ": question language must differ from target_language");
					}
					spec.documents().stream().filter(d -> relevant.containsKey(d.metadata().docKey()))
							.filter(d -> !d.metadata().language().equals("ar-msa".equals(t) ? "ar" : t))
							.forEach(d -> errors.add(id + ": relevant doc " + d.metadata().docKey() + " not in " + t));
				}
			}
			case "SECURITY" -> {
				if (c.expectedTool() != null && !ds.tools().contains(c.expectedTool())) {
					errors.add(id + ": unknown expected_tool " + c.expectedTool());
				}
				if ("ANSWER".equals(c.expectedBehavior()) && c.mustNotContain().isEmpty() && c.forbiddenTools().isEmpty()
						&& c.mustContain().isEmpty()) {
					errors.add(id + ": security ANSWER case needs a must_contain, must_not_contain or forbidden_tools check");
				}
			}
			default -> {
				if (!c.answerable() || c.facts().isEmpty() || c.mustContain().isEmpty()) {
					errors.add(id + ": factual/multi-hop cases need ANSWER, facts and must_contain");
				}
			}
		}
	}

	/** Numeric must_contain alternatives must come from the case's facts (answers cannot assert unplanned numbers). */
	private static void checkMustContainNumbers(BenchmarkCase c, Map<String, Fact> facts, List<String> errors) {
		if (c.facts().isEmpty() || c.expectedTool() != null) {
			return;
		}
		Set<String> allowed = new HashSet<>();
		c.facts().stream().map(facts::get).filter(java.util.Objects::nonNull).forEach(f -> allowed.addAll(f.allowedNumbers()));
		for (String item : c.mustContain()) {
			boolean ok = Arrays.stream(item.split("\\|")).anyMatch(alt -> {
				Set<String> nums = TextChecks.numbers(alt);
				return nums.isEmpty() || allowed.containsAll(nums);
			});
			if (!ok) {
				errors.add(c.id() + ": must_contain '" + item + "' has numbers not stated by its facts " + allowed);
			}
		}
	}

	/** No run of LEAKAGE_NGRAM consecutive question words may appear in any corpus document. */
	private static void checkLeakage(BenchmarkCase c, List<String> corpus, List<String> errors) {
		String[] words = TextChecks.squash(c.question().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " "))
				.split(" ");
		for (int i = 0; i + LEAKAGE_NGRAM <= words.length; i++) {
			String gram = String.join(" ", Arrays.copyOfRange(words, i, i + LEAKAGE_NGRAM));
			if (corpus.stream().anyMatch(doc -> doc.contains(gram))) {
				errors.add(c.id() + ": question leaks corpus text: '" + gram + "'");
				return;
			}
		}
	}

	/** Lower-cased corpus file contents with punctuation collapsed to single spaces (same normalization as questions). */
	static List<String> corpusTexts(Path corpusRoot) {
		try (Stream<Path> files = Files.walk(corpusRoot)) {
			return files.filter(p -> p.toString().endsWith(".md")).map(p -> {
				try {
					return " " + TextChecks.squash(Files.readString(p, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT)
							.replaceAll("[^\\p{L}\\p{N}]+", " ")) + " ";
				}
				catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}).toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
