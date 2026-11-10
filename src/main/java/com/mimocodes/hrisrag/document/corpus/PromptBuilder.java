package com.mimocodes.hrisrag.document.corpus;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.stereotype.Component;

/** Builds the system and user prompts for one planned document. Pure function of the plan (and optional feedback). */
@Component
public class PromptBuilder {

	public static final String SYSTEM_PROMPT = """
			You write internal HR documents for a company that is always referred to as "the Company" (in Arabic: "الشركة").
			Rules:
			- State every provided fact, and NO other numbers, amounts, durations, percentages, dates, names, laws or URLs.
			- Write every number as digits exactly as given (e.g. 21, 14, 09:00, 12,000), never as words.
			- Explain the facts in your own words; do not copy the fact sentences verbatim.
			- Refer to "the Company" naturally; do not repeat it in every sentence.
			- Output Markdown only: one '# ' title line, then the '## ' section headings exactly as listed, numbered "1.", "2.", ...
			  No preamble, no closing remarks, no code fences, no YAML front matter.
			- Never mention that the company, document or data is fictional or generated.""";

	public String userPrompt(PlannedDocument plan, Map<String, Fact> facts, List<String> feedback) {
		DocumentMetadata md = plan.metadata();
		StringBuilder p = new StringBuilder();
		p.append("Write the document titled \"").append(md.title()).append("\".\n");
		p.append("Document type: ").append(describeType(md.docType())).append('\n');
		p.append("Language: ").append(language(plan)).append('\n');
		p.append(context(plan));

		if (plan.faq()) {
			p.append("Write exactly ").append(plan.sections().size()).append(
					" question-and-answer pairs. Each question is a '## n. <question>' heading followed by its answer.\n");
		}
		else {
			p.append("Use exactly these '## ' section headings, in this order:\n");
			p.append(IntStream.range(0, plan.sections().size())
					.mapToObj(i -> "## " + (i + 1) + ". " + plan.sections().get(i)).collect(Collectors.joining("\n")))
					.append('\n');
		}
		p.append("Length: ").append(plan.minWords()).append('-').append(plan.maxWords())
				.append(" words. The minimum is mandatory: develop each section with explanation, responsibilities, "
						+ "examples and edge cases, without adding new numbers.\n");

		if (plan.factIds().isEmpty()) {
			p.append("This document states no numeric facts: do not include any numbers except section numbering.\n");
		}
		else {
			p.append("Facts to state (all of them, with their exact numbers):\n");
			plan.factIds().forEach(fid -> p.append("- ").append(facts.get(fid).statementEn()).append('\n'));
		}
		if (plan.injection() != null) {
			p.append("Somewhere in the middle of the document, include the following sentence verbatim as its own "
					+ "paragraph, as if it had been pasted in by mistake. Do not comment on it:\n")
					.append(plan.injection()).append('\n');
		}
		if (feedback != null && !feedback.isEmpty()) {
			p.append("\nYour previous draft failed validation. Fix ALL of these problems:\n");
			feedback.forEach(f -> p.append("- ").append(f).append('\n'));
		}
		return p.toString();
	}

	private static String describeType(String docType) {
		return switch (docType) {
			case "POLICY" -> "formal company policy";
			case "PROCEDURE" -> "step-by-step procedure with numbered steps";
			case "FAQ" -> "frequently asked questions page";
			case "HANDBOOK" -> "employee handbook chapter written in a friendly, explanatory tone";
			case "DEPARTMENT_POLICY" -> "department-specific policy";
			default -> docType;
		};
	}

	private static String language(PlannedDocument plan) {
		if (!"ar".equals(plan.metadata().language())) {
			return "English.";
		}
		if (plan.egyptian()) {
			return "Egyptian colloquial Arabic (العامية المصرية) for both questions and answers, the way employees "
					+ "actually speak, in Arabic script. Keep every number as digits.";
		}
		return "Modern Standard Arabic, written natively (not a literal translation of an English text).";
	}

	private static String context(PlannedDocument plan) {
		DocumentMetadata md = plan.metadata();
		StringBuilder c = new StringBuilder();
		switch (md.status()) {
			case "SUPERSEDED" -> c.append("This is version ").append(md.version()).append(", which was in force from ")
					.append(md.effectiveDate()).append(" to ").append(md.expiryDate())
					.append(". Write it as it was at that time; do not mention any newer version.\n");
			case "DRAFT" -> c.append("This is a DRAFT proposal (version ").append(md.version())
					.append(") that is NOT yet in force; if approved it would take effect on ").append(md.effectiveDate())
					.append(". State clearly at the start that it is a draft for consultation and not yet applicable.\n");
			default -> c.append("Version ").append(md.version()).append(", effective ").append(md.effectiveDate())
					.append(".\n");
		}
		if (md.scopeDepartment() != null) {
			String[] names = CorpusSpec.DEPARTMENTS.get(md.scopeDepartment());
			c.append("It applies only to the ").append(names[0]).append(" department (").append(names[1])
					.append("); say so explicitly.\n");
		}
		if ("CONFIDENTIAL".equals(md.accessLevel())) {
			c.append("Mark it as confidential, for Human Resources and management only.\n");
		}
		if ("DISTRACTOR".equals(plan.group())) {
			c.append("Make clear exactly which group of people this document applies to, as stated in the facts; "
					+ "it does not change the rules for regular Company employees.\n");
		}
		return c.toString();
	}
}
