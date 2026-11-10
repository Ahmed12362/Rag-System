package com.mimocodes.hrisrag.rag.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.rag.prompt.AgentPrompt;
import com.mimocodes.hrisrag.rag.prompt.GroundedPrompt;

/**
 * Last check on the model's answer before it is returned. It is a backstop for leaks the earlier layers should already
 * prevent; it is NOT the access-control boundary (restricted documents never reach the model, see {@link AccessPolicy}).
 * <ul>
 * <li>{@code SYSTEM_PROMPT_LEAK}: the answer repeats a rule line of the system prompt.</li>
 * <li>{@code SECRET_PATTERN}: the answer contains something shaped like a credential (Google API key, private key,
 * {@code password=...}).</li>
 * <li>{@code CONFIGURED_SECRET}: the answer contains the actual value of a configured secret (Gemini key, database or
 * demo password). The values are only compared in memory and never logged.</li>
 * </ul>
 */
@Component
public class OutputGuard {

	private static final int FRAGMENT_CHARS = 40;
	private static final List<Pattern> SECRET_PATTERNS = List.of(
			Pattern.compile("AIza[0-9A-Za-z_\\-]{30,}"),
			Pattern.compile("AQ\\.[0-9A-Za-z_\\-]{20,}"),
			Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
			Pattern.compile("(?i)\\b(api[_-]?key|password|passwd|secret|token)\\s*[:=]\\s*\\S{8,}"));

	private final List<String> promptFragments;
	private final List<String> secretValues;

	public OutputGuard(Environment env) {
		this.promptFragments = (GroundedPrompt.SYSTEM + "\n" + AgentPrompt.RULES).lines()
				.map(l -> TextNormalizer.normalize(l.replaceFirst("^\\s*-\\s*", "")))
				.filter(l -> l.length() >= FRAGMENT_CHARS)
				.map(l -> l.substring(0, FRAGMENT_CHARS))
				.toList();
		List<String> values = new ArrayList<>();
		for (String key : List.of("spring.ai.google.genai.api-key", "spring.ai.google.genai.embedding.api-key",
				"spring.datasource.password", "hrisrag.security.demo-password")) {
			String v = env.getProperty(key);
			if (v != null && v.strip().length() >= 8) {
				values.add(v.strip());
			}
		}
		this.secretValues = List.copyOf(values);
	}

	/** Empty when the answer may be returned; otherwise the rule id that blocks it. */
	public Optional<String> check(String answer) {
		if (answer == null || answer.isEmpty()) {
			return Optional.empty();
		}
		for (String secret : secretValues) {
			if (answer.contains(secret)) {
				return Optional.of("CONFIGURED_SECRET");
			}
		}
		for (Pattern p : SECRET_PATTERNS) {
			if (p.matcher(answer).find()) {
				return Optional.of("SECRET_PATTERN");
			}
		}
		String normalized = TextNormalizer.normalize(answer);
		for (String fragment : promptFragments) {
			if (normalized.contains(fragment)) {
				return Optional.of("SYSTEM_PROMPT_LEAK");
			}
		}
		return Optional.empty();
	}
}
