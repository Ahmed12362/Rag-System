package com.mimocodes.hrisrag.document.corpus;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Metadata of one corpus document: written as YAML front matter in the corpus file, and (M4) stored on
 * source_document. Contains nothing generation-specific (no facts, intent or benchmark labels).
 */
public record DocumentMetadata(
		String docKey,
		String version,
		String title,
		String category,
		String docType,
		String language,
		String status,
		LocalDate effectiveDate,
		LocalDate expiryDate,
		String scopeDepartment,
		String accessLevel) {

	public static final Set<String> DOC_TYPES = Set.of("POLICY", "PROCEDURE", "FAQ", "HANDBOOK", "DEPARTMENT_POLICY");
	public static final Set<String> LANGUAGES = Set.of("en", "ar");
	public static final Set<String> STATUSES = Set.of("ACTIVE", "SUPERSEDED", "DRAFT");
	public static final Set<String> ACCESS_LEVELS = Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL");

	private static final String FENCE = "---";

	/** The parsed content of a corpus file. */
	public record Parsed(DocumentMetadata metadata, String body) {
	}

	public String toFrontMatter() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("doc_key", docKey);
		m.put("version", version);
		m.put("title", title);
		m.put("category", category);
		m.put("doc_type", docType);
		m.put("language", language);
		m.put("status", status);
		m.put("effective_date", effectiveDate.toString());
		m.put("expiry_date", expiryDate == null ? null : expiryDate.toString());
		m.put("scope_department", scopeDepartment);
		m.put("access_level", accessLevel);
		DumperOptions options = new DumperOptions();
		options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
		options.setAllowUnicode(true);
		options.setWidth(1000);
		return FENCE + "\n" + new Yaml(options).dump(m) + FENCE + "\n";
	}

	/** Splits a corpus file into front matter metadata and Markdown body. */
	public static Parsed parse(String fileContent) {
		String content = fileContent.replace("\r\n", "\n");
		if (!content.startsWith(FENCE + "\n")) {
			throw new IllegalArgumentException("missing front matter");
		}
		int end = content.indexOf("\n" + FENCE + "\n", FENCE.length());
		if (end < 0) {
			throw new IllegalArgumentException("unterminated front matter");
		}
		Map<String, Object> m = new Yaml(new SafeConstructor(new LoaderOptions()))
				.load(content.substring(FENCE.length() + 1, end));
		DocumentMetadata md = new DocumentMetadata(str(m, "doc_key"), str(m, "version"), str(m, "title"),
				str(m, "category"), str(m, "doc_type"), str(m, "language"), str(m, "status"),
				date(m.get("effective_date")), date(m.get("expiry_date")), str(m, "scope_department"),
				str(m, "access_level"));
		return new Parsed(md, content.substring(end + FENCE.length() + 2));
	}

	static String str(Map<String, Object> m, String key) {
		Object v = m.get(key);
		return v == null ? null : v.toString();
	}

	static LocalDate date(Object v) {
		if (v == null) {
			return null;
		}
		if (v instanceof Date d) {
			return d.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
		}
		return LocalDate.parse(v.toString());
	}
}
