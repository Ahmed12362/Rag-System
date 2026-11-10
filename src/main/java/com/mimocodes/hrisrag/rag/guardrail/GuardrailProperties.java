package com.mimocodes.hrisrag.rag.guardrail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.guardrails.*}. The modes exist so the benchmark can measure each layer on its own (for example,
 * context-screening FLAG measures whether the model itself resists an injected document). Access control has no
 * switch: it is always enforced.
 *
 * @param inputInjection   BLOCK (default): a detected attack is refused before any model call; FLAG: recorded only;
 *                         OFF
 * @param contextScreening REDACT (default): injected sentences are removed from passages before they reach the model;
 *                         FLAG: recorded only, the passage is sent unchanged; OFF
 * @param outputCheck      block answers that leak the system prompt or secrets
 */
@ConfigurationProperties(prefix = "hrisrag.guardrails")
public record GuardrailProperties(
		@DefaultValue("BLOCK") Mode inputInjection,
		@DefaultValue("REDACT") ScreeningMode contextScreening,
		@DefaultValue("true") boolean outputCheck) {

	public enum Mode { BLOCK, FLAG, OFF }

	public enum ScreeningMode { REDACT, FLAG, OFF }
}
