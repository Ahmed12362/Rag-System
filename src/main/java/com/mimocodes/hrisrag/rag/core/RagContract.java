package com.mimocodes.hrisrag.rag.core;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The shared strategy contract (docs/architecture.md §6). Every strategy takes a {@link RagQuery} and returns a
 * {@link RagResult}; the chat API and the benchmark call strategies identically, so their outputs are comparable.
 * Grouped in one file because these are small, tightly related value types.
 */
public final class RagContract {

	private RagContract() {
	}

	public enum StrategyId { NAIVE, HYBRID, AGENTIC }

	public enum AnswerStatus { ANSWERED, ABSTAINED, BLOCKED, ERROR }

	/** Authenticated caller. Built from the server-side identity, never from request fields. */
	public record UserContext(String employeeCode, String role, String department) {
		public Set<String> visibleAccessLevels() {
			return "HR_ADMIN".equals(role) ? Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL") : Set.of("PUBLIC", "INTERNAL");
		}
	}

	/** What the user asked. Carries no benchmark labels (architecture rule 3). */
	public record RagQuery(String question, String conversationId, UserContext user) {
	}

	/** Per-request settings; recorded in every trace and experiment so results are reproducible. */
	public record RagSettings(int topK, int maxContextTokens, String chatModel, double temperature) {
	}

	public record RetrievedChunk(long chunkId, String docKey, String version, String title, String sectionPath,
			String language, String accessLevel, String content, double score, int rank) {
	}

	/** A validated citation: source id as shown to the model ("S2") resolved to the stored chunk. */
	public record Citation(String sourceId, long chunkId, String docKey, String version, String sectionPath) {
	}

	/** One provider call (embedding or generation) with its cost signals. */
	public record ModelCall(String purpose, String model, long latencyMs, Integer promptTokens, Integer completionTokens,
			Integer thinkingTokens, String finishReason, boolean cached) {
	}

	public record GuardrailDecision(String layer, String rule, String outcome, String detail) {
	}

	public record Timings(long totalMs, long embeddingMs, long retrievalMs, long generationMs) {
	}

	public record RagResult(UUID requestId, StrategyId strategy, AnswerStatus status, String answer,
			List<Citation> citations, List<RetrievedChunk> retrieved, String contextSent, List<ModelCall> modelCalls,
			List<GuardrailDecision> guardrails, Timings timings, RagSettings settings, String promptVersion) {
	}

	public interface RagStrategy {
		StrategyId id();

		RagResult execute(RagQuery query, RagSettings settings);
	}
}
