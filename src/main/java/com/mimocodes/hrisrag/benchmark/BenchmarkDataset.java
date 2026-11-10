package com.mimocodes.hrisrag.benchmark;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import com.mimocodes.hrisrag.document.corpus.CorpusManifest;
import com.mimocodes.hrisrag.document.corpus.CorpusSpec;
import com.mimocodes.hrisrag.document.corpus.PlannedDocument;

/**
 * A versioned benchmark dataset (benchmark/hris-bench-v*.yaml). Relevant documents are derived from each case's facts
 * using the corpus plan, so labels cannot drift away from the corpus.
 */
public record BenchmarkDataset(String name, String version, LocalDate referenceDate, Map<String, Integer> targets,
		Map<String, Asker> askers, List<String> tools, List<BenchmarkCase> cases, String sha256) {

	public record Asker(String role, String department) {
		/** EMPLOYEE / MANAGER see PUBLIC and INTERNAL documents; HR_ADMIN also CONFIDENTIAL. */
		public Set<String> visibleAccessLevels() {
			return "HR_ADMIN".equals(role) ? Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL") : Set.of("PUBLIC", "INTERNAL");
		}
	}

	static final Set<String> NON_RELEVANT_GROUPS = Set.of("DISTRACTOR", "INJECTION");
	static final Set<String> AUTHORITATIVE_GROUPS = Set.of("CORE", "DEPARTMENT");

	@SuppressWarnings("unchecked")
	public static BenchmarkDataset load(Path file) {
		Map<String, Object> y;
		try {
			y = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Map<String, Integer> targets = new LinkedHashMap<>();
		((Map<String, Object>) y.get("targets")).forEach((k, v) -> targets.put(k, ((Number) v).intValue()));
		Map<String, Asker> askers = new LinkedHashMap<>();
		((Map<String, Map<String, Object>>) y.get("askers"))
				.forEach((k, v) -> askers.put(k, new Asker(s(v.get("role")), s(v.get("department")))));
		List<BenchmarkCase> cases = new ArrayList<>();
		for (Map<String, Object> c : (List<Map<String, Object>>) y.get("cases")) {
			String behavior = c.get("expected_behavior") != null ? s(c.get("expected_behavior")) : "ANSWER";
			cases.add(new BenchmarkCase(s(c.get("id")), s(c.get("category")), s(c.get("language")), s(c.get("xling")),
					s(c.get("target_language")), s(c.get("asker")), s(c.get("about_department")), strings(c.get("facts")),
					s(c.get("question")), strings(c.get("must_contain")), strings(c.get("must_not_contain")), behavior,
					s(c.get("expected_auth")), s(c.get("expected_tool")), stringMap(c.get("tool_args")),
					strings(c.get("forbidden_tools")), intMap(c.get("docs")), strings(c.get("absent_terms")),
					s(c.get("reference")), s(c.get("note"))));
		}
		Object ref = y.get("reference_date");
		LocalDate referenceDate = ref instanceof java.util.Date d
				? d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate() : LocalDate.parse(s(ref));
		return new BenchmarkDataset(s(y.get("name")), s(y.get("version")), referenceDate, targets, askers,
				strings(y.get("tools")), List.copyOf(cases), CorpusManifest.sha256(file));
	}

	/**
	 * Relevant documents for a case: doc key → grade (2 = authoritative CORE/DEPARTMENT, 1 = supporting page).
	 * Derived from the case facts: ACTIVE, not a distractor or injection document, visible to the asker, in scope
	 * (company-wide, or the department the question is about / the asker belongs to), and in the target language.
	 */
	public Map<String, Integer> relevantDocs(BenchmarkCase c, CorpusSpec spec) {
		if (!c.docs().isEmpty()) {
			return c.docs();
		}
		Map<String, Integer> out = new LinkedHashMap<>();
		if (c.facts().isEmpty()) {
			return out;
		}
		Asker asker = askers.get(c.asker());
		String department = c.aboutDepartment() != null ? c.aboutDepartment() : asker.department();
		for (PlannedDocument d : spec.documents()) {
			var md = d.metadata();
			boolean candidate = "ACTIVE".equals(md.status()) && !NON_RELEVANT_GROUPS.contains(d.group())
					&& asker.visibleAccessLevels().contains(md.accessLevel())
					&& (md.scopeDepartment() == null || md.scopeDepartment().equals(department))
					&& matchesTargetLanguage(d, c.targetLanguage())
					&& d.factIds().stream().anyMatch(c.facts()::contains);
			if (candidate) {
				out.put(md.docKey(), AUTHORITATIVE_GROUPS.contains(d.group()) ? 2 : 1);
			}
		}
		return out;
	}

	private static boolean matchesTargetLanguage(PlannedDocument d, String target) {
		if (target == null) {
			return true;
		}
		return switch (target) {
			case "ar-msa" -> "ar".equals(d.metadata().language()) && !d.egyptian();
			default -> target.equals(d.metadata().language());
		};
	}

	// ---- YAML helpers ----

	private static String s(Object o) {
		return o == null ? null : o.toString();
	}

	private static List<String> strings(Object o) {
		if (o == null) {
			return List.of();
		}
		return ((List<?>) o).stream().map(Object::toString).toList();
	}

	private static Map<String, String> stringMap(Object o) {
		Map<String, String> m = new LinkedHashMap<>();
		if (o != null) {
			((Map<?, ?>) o).forEach((k, v) -> m.put(k.toString(), v.toString()));
		}
		return m;
	}

	private static Map<String, Integer> intMap(Object o) {
		Map<String, Integer> m = new LinkedHashMap<>();
		if (o != null) {
			((Map<?, ?>) o).forEach((k, v) -> m.put(k.toString(), ((Number) v).intValue()));
		}
		return m;
	}
}
