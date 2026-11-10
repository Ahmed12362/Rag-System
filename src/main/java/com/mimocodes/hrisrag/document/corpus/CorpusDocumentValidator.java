package com.mimocodes.hrisrag.document.corpus;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Validates one generated document against its plan. Every failure message is phrased so it can be fed back to the
 * LLM as a correction instruction.
 */
@Component
public class CorpusDocumentValidator {

	static final double MIN_WORDS_FACTOR = 0.9;
	static final double MAX_WORDS_FACTOR = 1.4;
	static final double MIN_ARABIC_RATIO = 0.85;
	static final double MAX_ARABIC_RATIO_EN = 0.02;

	private static final Pattern H1 = Pattern.compile("(?m)^# (.+)$");
	private static final Pattern H2 = Pattern.compile("(?m)^## (.+)$");

	/** Validates a complete corpus file (front matter + body). */
	public List<String> validateFile(PlannedDocument plan, String fileContent, Map<String, Fact> facts) {
		DocumentMetadata.Parsed parsed;
		try {
			parsed = DocumentMetadata.parse(fileContent);
		}
		catch (RuntimeException e) {
			return List.of("front matter is unreadable: " + e.getMessage());
		}
		List<String> errors = new ArrayList<>();
		if (!parsed.metadata().equals(plan.metadata())) {
			errors.add("front matter does not match the planned metadata");
		}
		errors.addAll(validateBody(plan, parsed.body(), facts));
		return errors;
	}

	/** Validates the Markdown body produced by the LLM. */
	public List<String> validateBody(PlannedDocument plan, String body, Map<String, Fact> facts) {
		List<String> errors = new ArrayList<>();
		DocumentMetadata md = plan.metadata();

		if (body.contains("```")) {
			errors.add("Remove code fences: output plain Markdown only.");
		}
		if (body.stripLeading().startsWith("---")) {
			errors.add("Do not output YAML front matter.");
		}
		long h1 = H1.matcher(body).results().count();
		if (h1 != 1) {
			errors.add("Use exactly one '# ' title line (found " + h1 + ").");
		}
		else {
			String heading = H1.matcher(body).results().findFirst().orElseThrow().group(1).trim();
			if (!TextChecks.squash(heading).contains(TextChecks.squash(md.title()))) {
				errors.add("The '# ' title must be exactly: " + md.title());
			}
		}
		long h2 = H2.matcher(body).results().count();
		if (h2 != plan.sections().size()) {
			errors.add("Use exactly " + plan.sections().size() + " '## ' section headings (found " + h2 + ").");
		}

		int words = TextChecks.wordCount(body);
		int min = (int) Math.floor(plan.minWords() * MIN_WORDS_FACTOR);
		int max = (int) Math.ceil(plan.maxWords() * MAX_WORDS_FACTOR);
		if (words < min) {
			errors.add("The document is too short (" + words + " words); write at least " + plan.minWords() + " words.");
		}
		if (words > max) {
			errors.add("The document is too long (" + words + " words); stay within " + plan.maxWords() + " words.");
		}

		double arabic = TextChecks.arabicRatio(body);
		if ("ar".equals(md.language()) && arabic < MIN_ARABIC_RATIO) {
			errors.add("Write the document in Arabic (Arabic script ratio " + round(arabic) + ").");
		}
		if ("en".equals(md.language()) && arabic > MAX_ARABIC_RATIO_EN) {
			errors.add("Write the document in English only (Arabic script ratio " + round(arabic) + ").");
		}

		if (plan.injection() != null
				&& !TextChecks.squash(body).contains(TextChecks.squash(plan.injection()))) {
			errors.add("Include this sentence verbatim as its own paragraph: " + plan.injection());
		}

		Set<String> found = TextChecks.numbers(body);
		Set<String> allowed = allowedNumbers(plan, facts);
		for (String fid : plan.factIds()) {
			Fact f = facts.get(fid);
			Set<String> missing = new TreeSet<>(f.requiredNumbers());
			missing.removeAll(found);
			if (!missing.isEmpty()) {
				errors.add("State this fact with its exact number(s) " + missing + ": " + f.statementEn());
			}
		}
		Set<String> unexpected = new TreeSet<>(found);
		unexpected.removeAll(allowed);
		if (!unexpected.isEmpty()) {
			errors.add("Remove numbers that are not in the provided facts: " + unexpected + ".");
		}
		return errors;
	}

	/** Numbers a document may contain: fact values and statements, section numbers, dates, version, title, injection. */
	static Set<String> allowedNumbers(PlannedDocument plan, Map<String, Fact> facts) {
		DocumentMetadata md = plan.metadata();
		Set<String> allowed = new LinkedHashSet<>();
		plan.factIds().forEach(fid -> allowed.addAll(facts.get(fid).allowedNumbers()));
		for (int i = 1; i <= plan.sections().size(); i++) {
			allowed.add(String.valueOf(i));
		}
		allowed.addAll(TextChecks.numbers(md.effectiveDate().toString()));
		if (md.expiryDate() != null) {
			allowed.addAll(TextChecks.numbers(md.expiryDate().toString()));
		}
		allowed.addAll(TextChecks.numbers(md.version()));
		allowed.addAll(TextChecks.numbers(md.version().replace(".0", "")));
		allowed.addAll(TextChecks.numbers(md.title()));
		allowed.addAll(TextChecks.numbers(plan.injection()));
		return allowed;
	}

	private static String round(double v) {
		return String.format("%.2f", v);
	}
}
