package com.mimocodes.hrisrag.document.corpus;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * One entry of corpus-spec/facts.yaml.
 *
 * @param value           the fact's value. Its digits are REQUIRED in every document listing the fact.
 * @param statementEn     canonical English statement. Numbers in it are ALLOWED (not required) in the document.
 * @param superseded      belongs to an older document version only
 * @param draft           belongs to a draft (not in force) only
 * @param scopeDepartment set when the fact applies to one department only
 */
public record Fact(String id, String value, String statementEn, String rule, boolean superseded, boolean draft,
		String scopeDepartment) {

	public boolean distractor() {
		return id.startsWith("DIST_");
	}

	public Set<String> requiredNumbers() {
		return TextChecks.numbers(value);
	}

	public Set<String> allowedNumbers() {
		Set<String> all = new LinkedHashSet<>(TextChecks.numbers(value));
		all.addAll(TextChecks.numbers(statementEn));
		return all;
	}
}
