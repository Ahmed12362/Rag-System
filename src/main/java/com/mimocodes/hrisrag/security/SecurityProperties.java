package com.mimocodes.hrisrag.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code hrisrag.security.*}.
 *
 * @param demoPassword shared password of the simulated users, from {@code APP_DEMO_PASSWORD} in the git-ignored
 *                     {@code .env}. Stored only as a BCrypt hash; never logged. Blank means nobody can log in.
 */
@ConfigurationProperties(prefix = "hrisrag.security")
public record SecurityProperties(String demoPassword) {

	@Override
	public String toString() {
		return "SecurityProperties[demoPassword=" + (demoPassword == null || demoPassword.isBlank() ? "<unset>" : "***")
				+ "]";
	}
}
