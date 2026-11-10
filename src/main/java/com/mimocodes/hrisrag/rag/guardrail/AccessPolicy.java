package com.mimocodes.hrisrag.rag.guardrail;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.rag.core.RagContract.RetrievedChunk;
import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/**
 * Document access rules, in one place. The PRIMARY enforcement is inside the retrieval SQL (decision D12), so restricted
 * text never leaves the database for an unauthorized user. Strategies call {@link #denialReason} again on every chunk
 * BEFORE building the LLM context, as a second, independent check: a future retriever (hybrid, keyword, tool) that
 * forgets a filter cannot leak a restricted passage to the model. Answer-side checks are never relied on for access.
 */
@Component
public class AccessPolicy {

	/** Empty when the user may read the chunk; otherwise the rule that denies it. */
	public Optional<String> denialReason(UserContext user, RetrievedChunk chunk) {
		if (!"ACTIVE".equals(chunk.status())) {
			return Optional.of("STATUS_NOT_ACTIVE");
		}
		if (!user.visibleAccessLevels().contains(chunk.accessLevel())) {
			return Optional.of("ACCESS_LEVEL_ABOVE_USER");
		}
		if (chunk.scopeDepartment() != null && !chunk.scopeDepartment().equals(user.department())) {
			return Optional.of("OTHER_DEPARTMENT");
		}
		return Optional.empty();
	}
}
