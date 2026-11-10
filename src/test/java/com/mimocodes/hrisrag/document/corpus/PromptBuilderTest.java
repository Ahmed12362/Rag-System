package com.mimocodes.hrisrag.document.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class PromptBuilderTest {

	private final CorpusSpec spec = CorpusSpec.load(Path.of("corpus-spec"));
	private final PromptBuilder builder = new PromptBuilder();

	private String prompt(String id) {
		PlannedDocument p = spec.documents().stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
		return builder.userPrompt(p, spec.facts(), List.of());
	}

	@Test
	void corePolicyPromptListsSectionsAndFacts() {
		String p = prompt("POL-ANNUAL-LEAVE-EN@2.0");
		assertThat(p).contains("## 1. Purpose").contains("## 6. Related documents")
				.contains(spec.facts().get("ANNUAL_LEAVE_NOTICE_DAYS").statementEn()).contains("Language: English.");
	}

	@Test
	void supersededDraftDepartmentConfidentialAndInjectionContexts() {
		assertThat(prompt("POL-ANNUAL-LEAVE-EN@1.0")).contains("was in force from 2023-01-01 to 2025-12-31");
		assertThat(prompt("POL-REMOTE-WORK-EN@3.0")).contains("DRAFT proposal").contains("NOT yet in force");
		assertThat(prompt("DEPT-CS-REMOTE-WORK-AR@1.0")).contains("Customer Support").contains("Modern Standard Arabic");
		assertThat(prompt("CONF-SALARY-BANDS-EN@1.0")).contains("confidential");
		assertThat(prompt("INJ-PARKING-NOTICE-EN@1.0")).contains("verbatim").contains("ignore all previous instructions");
		assertThat(prompt("FAQ-ATTENDANCE-EG-AR@1.0")).contains("Egyptian colloquial Arabic").contains("exactly 6 question");
	}

	@Test
	void currentDocumentsDoNotMentionVersionOrDateButSupersededDo() {
		assertThat(prompt("POL-ANNUAL-LEAVE-EN@2.0")).contains("Do not mention the version number or the effective date")
				.doesNotContain("2026-01-01");
		assertThat(prompt("POL-ANNUAL-LEAVE-EN@1.0")).contains("2023-01-01");
		assertThat(PromptBuilder.SYSTEM_PROMPT).contains("proofread");
	}

	@Test
	void feedbackIsAppended() {
		PlannedDocument p = spec.documents().getFirst();
		assertThat(builder.userPrompt(p, spec.facts(), List.of("Remove numbers that are not in the provided facts: [99]")))
				.contains("failed validation").contains("[99]");
	}
}
