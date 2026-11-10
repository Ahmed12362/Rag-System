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

	/** Document classifications in increasing order of sensitivity. */
	public static final List<String> ACCESS_LEVELS = List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL");

	/**
	 * Authenticated caller. Built only from the authenticated principal (Spring Security), never from request fields.
	 *
	 * @param maxAccessLevel highest classification the user may read (PUBLIC, INTERNAL or CONFIDENTIAL)
	 */
	public record UserContext(String employeeCode, String role, String department, String maxAccessLevel) {
		public UserContext {
			if (!ACCESS_LEVELS.contains(maxAccessLevel)) {
				throw new IllegalArgumentException("unknown access level " + maxAccessLevel);
			}
		}

		public Set<String> visibleAccessLevels() {
			return Set.copyOf(ACCESS_LEVELS.subList(0, ACCESS_LEVELS.indexOf(maxAccessLevel) + 1));
		}
	}

	/** What the user asked. Carries no benchmark labels (architecture rule 3). */
	public record RagQuery(String question, String conversationId, UserContext user) {
	}

	/** Per-request settings; recorded in every trace and experiment so results are reproducible. */
	public record RagSettings(int topK, int maxContextTokens, String chatModel, double temperature) {
	}

	/**
	 * A retrieved chunk exactly as stored (retrieval metrics use these, before any guardrail screening).
	 *
	 * @param score       the ranking score of the retriever that produced the list (cosine similarity for vector,
	 *                    ts_rank_cd for keyword, the RRF score for hybrid)
	 * @param vectorRank  1-based rank in the vector candidate list, null if not found there (hybrid only)
	 * @param keywordRank 1-based rank in the keyword candidate list, null if not found there (hybrid only)
	 */
	public record RetrievedChunk(long chunkId, String docKey, String version, String title, String sectionPath,
			String language, String status, String accessLevel, String scopeDepartment, String content, double score,
			int rank, Integer vectorRank, Integer keywordRank) {

		public RetrievedChunk(long chunkId, String docKey, String version, String title, String sectionPath,
				String language, String status, String accessLevel, String scopeDepartment, String content, double score,
				int rank) {
			this(chunkId, docKey, version, title, sectionPath, language, status, accessLevel, scopeDepartment, content,
					score, rank, null, null);
		}

		public RetrievedChunk withContent(String newContent) {
			return new RetrievedChunk(chunkId, docKey, version, title, sectionPath, language, status, accessLevel,
					scopeDepartment, newContent, score, rank, vectorRank, keywordRank);
		}

		public RetrievedChunk ranked(double newScore, int newRank, Integer newVectorRank, Integer newKeywordRank) {
			return new RetrievedChunk(chunkId, docKey, version, title, sectionPath, language, status, accessLevel,
					scopeDepartment, content, newScore, newRank, newVectorRank, newKeywordRank);
		}
	}

	/**
	 * What the guardrails did with one retrieved passage before context construction, so the benchmark can tell
	 * "retrieved" apart from "sent to the model".
	 *
	 * @param outcome CLEAN (sent unchanged), FLAGGED (sent unchanged, suspicious lines detected), REDACTED (sent with the
	 *                flagged sentences removed), REMOVED (nothing left to send) or DENIED (failed the access re-check)
	 * @param rules   rule ids that matched, empty when CLEAN
	 * @param removedSentences number of sentences removed from the passage
	 */
	public record PassageCheck(long chunkId, String docKey, int rank, String outcome, List<String> rules,
			int removedSentences) {
	}

	/**
	 * Provider calls of one request, counted separately for benchmark cost reporting. A cached query embedding is
	 * counted in {@code embeddingCacheHits}, not in {@code embeddingCalls} (it costs no quota).
	 */
	public record CallCounts(int embeddingCalls, int embeddingCacheHits, int chatCalls, int promptTokens,
			int completionTokens, int thinkingTokens) {

		public static CallCounts of(List<ModelCall> calls) {
			int emb = 0, hits = 0, chat = 0, p = 0, c = 0, t = 0;
			for (ModelCall m : calls) {
				if (m.purpose().startsWith("EMBED")) {
					if (m.cached()) {
						hits++;
					}
					else {
						emb++;
					}
				}
				else {
					chat++;
					p += m.promptTokens() == null ? 0 : m.promptTokens();
					c += m.completionTokens() == null ? 0 : m.completionTokens();
					t += m.thinkingTokens() == null ? 0 : m.thinkingTokens();
				}
			}
			return new CallCounts(emb, hits, chat, p, c, t);
		}
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

	/**
	 * @param retrieved     chunks as retrieved (input for retrieval-quality metrics)
	 * @param passageChecks guardrail outcome per retrieved chunk (input for security metrics)
	 * @param contextSent   the source text actually sent to the model, after screening
	 */
	public record RagResult(UUID requestId, StrategyId strategy, AnswerStatus status, String answer,
			List<Citation> citations, List<RetrievedChunk> retrieved, List<PassageCheck> passageChecks,
			String contextSent, List<ModelCall> modelCalls, CallCounts callCounts, List<GuardrailDecision> guardrails,
			Timings timings, RagSettings settings, String promptVersion) {
	}

	public interface RagStrategy {
		StrategyId id();

		RagResult execute(RagQuery query, RagSettings settings);
	}
}
