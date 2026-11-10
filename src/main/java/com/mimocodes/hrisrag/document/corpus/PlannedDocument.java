package com.mimocodes.hrisrag.document.corpus;

import java.util.List;
import java.util.Locale;

/**
 * One expanded entry of corpus-spec/documents.yaml: the metadata to write plus generation-only instructions
 * (group, facts, sections, length, intent, dialect, injection). Generation fields never reach the corpus file.
 *
 * @param temperature optional per-document override of the generation temperature (null = global setting)
 * @param voice       optional per-document writing-voice instruction (null = none). Used only for documents that
 *                    failed with evidence of a provider stop such as RECITATION.
 */
public record PlannedDocument(
		DocumentMetadata metadata,
		String group,
		List<String> factIds,
		List<String> sections,
		boolean faq,
		int minWords,
		int maxWords,
		String intent,
		String dialect,
		String injection,
		Double temperature,
		String voice) {

	/** Stable identity: doc key + version. */
	public String id() {
		return metadata.docKey() + "@" + metadata.version();
	}

	/** Path relative to the corpus root: {lang}/{category}/{doc_key}--v{version}.md */
	public String relativePath() {
		return metadata.language() + "/" + metadata.category().toLowerCase(Locale.ROOT).replace('_', '-') + "/"
				+ metadata.docKey() + "--v" + metadata.version() + ".md";
	}

	public boolean egyptian() {
		return "egyptian".equalsIgnoreCase(dialect);
	}
}
