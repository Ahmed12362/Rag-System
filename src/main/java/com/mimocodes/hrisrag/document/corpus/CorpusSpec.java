package com.mimocodes.hrisrag.document.corpus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The corpus specification: facts.yaml + documents.yaml, expanded into one {@link PlannedDocument} per corpus file.
 */
public record CorpusSpec(Map<String, Fact> facts, List<PlannedDocument> documents) {

	public static final List<String> GROUPS = List.of("CORE", "SUPERSEDED", "PROCEDURE", "FAQ", "HANDBOOK",
			"DEPARTMENT", "CONFIDENTIAL", "DRAFT", "DISTRACTOR", "INJECTION");

	/** Departments of the STANDARD seed profile; department-scoped documents must target one of these. */
	public static final Map<String, String[]> DEPARTMENTS = Map.of(
			"D-HR", new String[] { "Human Resources", "الموارد البشرية" },
			"D-ENG", new String[] { "Engineering", "الهندسة" },
			"D-FIN", new String[] { "Finance", "المالية" },
			"D-SAL", new String[] { "Sales", "المبيعات" },
			"D-MKT", new String[] { "Marketing", "التسويق" },
			"D-OPS", new String[] { "Operations", "العمليات" },
			"D-CS", new String[] { "Customer Support", "دعم العملاء" },
			"D-LEG", new String[] { "Legal", "الشؤون القانونية" },
			"D-IT", new String[] { "IT", "تقنية المعلومات" },
			"D-PRC", new String[] { "Procurement", "المشتريات" });

	/** Drafts must take effect after the dataset reference date. */
	static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 11, 1);

	// ---------------------------------------------------------------------------------------------------- loading

	public static CorpusSpec load(Path specDir) {
		Map<String, Object> factsYaml = read(specDir.resolve("facts.yaml"));
		Map<String, Object> docsYaml = read(specDir.resolve("documents.yaml"));

		Map<String, Fact> facts = new LinkedHashMap<>();
		List<String> duplicateFacts = new ArrayList<>();
		for (Map<String, Object> f : listOfMaps(factsYaml.get("facts"))) {
			Fact fact = new Fact(s(f.get("id")), s(f.get("value")), s(f.get("statement_en")), s(f.get("rule")),
					Boolean.TRUE.equals(f.get("superseded")), Boolean.TRUE.equals(f.get("draft")),
					s(f.get("scope_department")));
			if (facts.put(fact.id(), fact) != null) {
				duplicateFacts.add(fact.id());
			}
		}
		if (!duplicateFacts.isEmpty()) {
			throw new IllegalStateException("duplicate fact ids: " + duplicateFacts);
		}

		Map<String, Object> defaults = map(docsYaml.get("defaults"));
		Map<String, Object> templates = map(docsYaml.get("section_templates"));
		Map<String, Object> words = map(docsYaml.get("words"));
		List<PlannedDocument> docs = new ArrayList<>();
		for (Map<String, Object> e : listOfMaps(docsYaml.get("documents"))) {
			Map<String, Object> entry = new HashMap<>(defaults);
			entry.putAll(e);
			String type = s(entry.get("type"));
			for (Object langObj : (List<?>) entry.get("langs")) {
				String lang = s(langObj);
				docs.add(expand(entry, type, lang, templates, words));
			}
		}
		return new CorpusSpec(facts, List.copyOf(docs));
	}

	private static PlannedDocument expand(Map<String, Object> e, String type, String lang, Map<String, Object> templates,
			Map<String, Object> words) {
		Object titleObj = e.get("title");
		String title = titleObj instanceof Map<?, ?> m ? s(m.get(lang)) : s(titleObj);
		DocumentMetadata md = new DocumentMetadata(
				s(e.get("key")) + "-" + lang.toUpperCase(Locale.ROOT), s(e.get("version")), title, s(e.get("cat")), type,
				lang, s(e.get("status")), DocumentMetadata.date(e.get("effective")), DocumentMetadata.date(e.get("expiry")),
				s(e.get("scope")), s(e.get("access")));

		boolean faq = "FAQ".equals(type);
		List<String> sections = new ArrayList<>();
		if (e.get("sections") != null) {
			((List<?>) e.get("sections")).forEach(x -> sections.add(s(x)));
		}
		else if (faq) {
			int n = ((Number) map(templates.get("FAQ")).get("questions")).intValue();
			for (int i = 1; i <= n; i++) {
				sections.add("Question " + i);
			}
		}
		else {
			((List<?>) map(templates.get(type)).get(lang)).forEach(x -> sections.add(s(x)));
		}
		List<?> range = (List<?>) (e.get("words") != null ? e.get("words") : words.get(type));
		List<String> factIds = new ArrayList<>();
		if (e.get("facts") != null) {
			((List<?>) e.get("facts")).forEach(x -> factIds.add(s(x)));
		}
		return new PlannedDocument(md, s(e.get("group")), List.copyOf(factIds), List.copyOf(sections), faq,
				((Number) range.get(0)).intValue(), ((Number) range.get(1)).intValue(), s(e.get("intent")),
				s(e.get("dialect")), s(e.get("injection")));
	}

	// ------------------------------------------------------------------------------------------------- validation

	/** Structural consistency of the whole spec. Empty list = valid. */
	public List<String> validate() {
		List<String> errors = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		Map<String, Integer> active = new HashMap<>();
		Set<String> usedFacts = new HashSet<>();

		for (PlannedDocument d : documents) {
			DocumentMetadata m = d.metadata();
			String id = d.id();
			if (!ids.add(id)) {
				errors.add(id + ": duplicate (doc_key, version)");
			}
			if (m.title() == null || m.title().isBlank()) {
				errors.add(id + ": missing title");
			}
			check(errors, id, "doc_type", m.docType(), DocumentMetadata.DOC_TYPES);
			check(errors, id, "language", m.language(), DocumentMetadata.LANGUAGES);
			check(errors, id, "status", m.status(), DocumentMetadata.STATUSES);
			check(errors, id, "access_level", m.accessLevel(), DocumentMetadata.ACCESS_LEVELS);
			check(errors, id, "group", d.group(), Set.copyOf(GROUPS));
			if (m.category() == null || m.effectiveDate() == null || m.version() == null) {
				errors.add(id + ": category, version and effective_date are required");
			}
			if ("ACTIVE".equals(m.status())) {
				active.merge(m.docKey(), 1, Integer::sum);
			}
			if ("SUPERSEDED".equals(m.status())
					&& (m.expiryDate() == null || !m.expiryDate().isAfter(m.effectiveDate()))) {
				errors.add(id + ": SUPERSEDED requires expiry_date after effective_date");
			}
			if ("DRAFT".equals(m.status()) && !m.effectiveDate().isAfter(REFERENCE_DATE)) {
				errors.add(id + ": DRAFT must have a future effective_date");
			}
			if (m.scopeDepartment() != null && !DEPARTMENTS.containsKey(m.scopeDepartment())) {
				errors.add(id + ": unknown scope_department " + m.scopeDepartment());
			}
			if ("DEPARTMENT".equals(d.group()) != (m.scopeDepartment() != null)) {
				errors.add(id + ": DEPARTMENT group and scope_department must go together");
			}
			if ("CONFIDENTIAL".equals(d.group()) != "CONFIDENTIAL".equals(m.accessLevel())) {
				errors.add(id + ": CONFIDENTIAL group and access_level must go together");
			}
			if ("INJECTION".equals(d.group()) != (d.injection() != null && !d.injection().isBlank())) {
				errors.add(id + ": injection text required exactly for INJECTION documents");
			}
			if (d.sections().isEmpty() || d.minWords() <= 0 || d.maxWords() < d.minWords()) {
				errors.add(id + ": sections and a valid word range are required");
			}
			for (String fid : d.factIds()) {
				Fact f = facts.get(fid);
				if (f == null) {
					errors.add(id + ": unknown fact " + fid);
					continue;
				}
				usedFacts.add(fid);
				if (f.superseded() && !"SUPERSEDED".equals(m.status())) {
					errors.add(id + ": superseded fact " + fid + " used in a non-superseded document");
				}
				if (f.draft() && !"DRAFT".equals(m.status())) {
					errors.add(id + ": draft fact " + fid + " used in a non-draft document");
				}
				if (f.distractor() && !"DISTRACTOR".equals(d.group())) {
					errors.add(id + ": distractor fact " + fid + " used outside DISTRACTOR documents");
				}
				if (f.scopeDepartment() != null && !f.scopeDepartment().equals(m.scopeDepartment())) {
					errors.add(id + ": department fact " + fid + " used outside " + f.scopeDepartment());
				}
			}
		}
		active.forEach((key, n) -> {
			if (n > 1) {
				errors.add(key + ": " + n + " ACTIVE versions");
			}
		});
		documents.stream().filter(d -> !"ACTIVE".equals(d.metadata().status()))
				.filter(d -> !active.containsKey(d.metadata().docKey()) && "SUPERSEDED".equals(d.metadata().status()))
				.forEach(d -> errors.add(d.id() + ": SUPERSEDED version without an ACTIVE successor"));
		facts.keySet().stream().filter(f -> !usedFacts.contains(f))
				.forEach(f -> errors.add("fact " + f + " is not used by any document"));
		return errors;
	}

	private static void check(List<String> errors, String id, String field, String value, Set<String> allowed) {
		if (value == null || !allowed.contains(value)) {
			errors.add(id + ": invalid " + field + " '" + value + "'");
		}
	}

	// ------------------------------------------------------------------------------------------------- selection

	/** Pilot batch: per group the first English and first Arabic document, plus the first Egyptian-dialect document. */
	public List<PlannedDocument> pilotSelection() {
		List<PlannedDocument> out = new ArrayList<>();
		for (String group : GROUPS) {
			for (String lang : List.of("en", "ar")) {
				documents.stream().filter(d -> d.group().equals(group) && d.metadata().language().equals(lang))
						.filter(d -> !d.egyptian()).findFirst().ifPresent(out::add);
			}
		}
		documents.stream().filter(PlannedDocument::egyptian).findFirst().ifPresent(out::add);
		return out;
	}

	/** Selects by doc id (KEY@version), doc key, or "group:NAME". */
	public List<PlannedDocument> select(List<String> selectors) {
		return documents.stream().filter(d -> selectors.stream().anyMatch(sel -> sel.equals(d.id())
				|| sel.equals(d.metadata().docKey()) || sel.equalsIgnoreCase("group:" + d.group()))).toList();
	}

	public Map<String, Integer> countsByGroup() {
		Map<String, Integer> m = new LinkedHashMap<>();
		GROUPS.forEach(g -> m.put(g, (int) documents.stream().filter(d -> d.group().equals(g)).count()));
		return m;
	}

	public Map<String, Integer> countsByLanguage() {
		Map<String, Integer> m = new TreeMap<>();
		documents.forEach(d -> m.merge(d.metadata().language(), 1, Integer::sum));
		return m;
	}

	// --------------------------------------------------------------------------------------------------- helpers

	private static Map<String, Object> read(Path file) {
		try {
			return new Yaml(new SafeConstructor(new LoaderOptions()))
					.load(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot read " + file, e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object o) {
		return o == null ? Map.of() : (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> listOfMaps(Object o) {
		return o == null ? List.of() : (List<Map<String, Object>>) o;
	}

	private static String s(Object o) {
		return o == null ? null : o.toString();
	}
}
