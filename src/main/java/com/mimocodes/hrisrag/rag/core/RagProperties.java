package com.mimocodes.hrisrag.rag.core;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.rag.*}. The dev user is a temporary, SERVER-SIDE identity used until Spring Security arrives (M6);
 * the API never accepts identity from the request.
 *
 * @param maxContextTokens estimated-token budget for the sources sent to the model
 */
@ConfigurationProperties(prefix = "hrisrag.rag")
public record RagProperties(
		@DefaultValue("5") int topK,
		@DefaultValue("2500") int maxContextTokens,
		@DefaultValue("gemini-3.5-flash-lite") String chatModel,
		@DefaultValue("0.0") double temperature,
		@DefaultValue("2000") int maxQuestionChars,
		@DefaultValue("60s") Duration generationTimeout,
		@DefaultValue DevUser devUser) {

	public record DevUser(@DefaultValue("E-1001") String employeeCode, @DefaultValue("EMPLOYEE") String role,
			@DefaultValue("D-ENG") String department) {
	}

	public RagContract.RagSettings defaultSettings() {
		return new RagContract.RagSettings(topK, maxContextTokens, chatModel, temperature);
	}

	public RagContract.UserContext devUserContext() {
		return new RagContract.UserContext(devUser.employeeCode(), devUser.role(), devUser.department());
	}
}
