package com.mimocodes.hrisrag.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/**
 * An authenticated simulated user. {@link #toUserContext()} is the ONLY way the API builds the RAG caller identity.
 * {@code toString} never includes the password hash.
 */
public final class AppUserPrincipal implements UserDetails {

	private final String username;
	private final String passwordHash;
	private final boolean enabled;
	private final UserContext context;

	public AppUserPrincipal(String username, String passwordHash, boolean enabled, UserContext context) {
		this.username = username;
		this.passwordHash = passwordHash;
		this.enabled = enabled;
		this.context = context;
	}

	public UserContext toUserContext() {
		return context;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + context.role()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public boolean isEnabled() {
		return enabled;
	}

	@Override
	public String toString() {
		return "AppUserPrincipal[" + username + ", " + context.employeeCode() + ", " + context.role() + "]";
	}
}
