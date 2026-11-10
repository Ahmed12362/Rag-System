package com.mimocodes.hrisrag.security;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Repository;

import com.mimocodes.hrisrag.rag.core.RagContract.UserContext;

/** {@code app_user} access, and Spring Security's {@link UserDetailsService}. */
@Repository
public class AppUserStore implements UserDetailsService {

	private final JdbcTemplate jdbc;

	public AppUserStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record StoredUser(long id, String username, String passwordHash) {
	}

	public Optional<AppUserPrincipal> find(String username) {
		return findBy("username", username);
	}

	/** Used by the benchmark to act as a case's asker (programmatic, no login). */
	public Optional<UserContext> contextForEmployee(String employeeCode) {
		return findBy("employee_code", employeeCode).map(AppUserPrincipal::toUserContext);
	}

	private Optional<AppUserPrincipal> findBy(String column, String value) {
		return jdbc.query("""
				SELECT username, employee_code, role, department_code, max_access_level, password_hash, enabled
				FROM app_user WHERE %s = ?""".formatted(column), (rs, i) -> new AppUserPrincipal(rs.getString("username"),
				rs.getString("password_hash"), rs.getBoolean("enabled"), new UserContext(rs.getString("employee_code"),
						rs.getString("role"), rs.getString("department_code"), rs.getString("max_access_level"))),
				value).stream().findFirst();
	}

	/** Users without a password hash cannot log in; they are reported as unknown (no account enumeration). */
	@Override
	public AppUserPrincipal loadUserByUsername(String username) {
		return find(username).filter(u -> u.getPassword() != null)
				.orElseThrow(() -> new UsernameNotFoundException("bad credentials"));
	}

	public List<StoredUser> all() {
		return jdbc.query("SELECT id, username, password_hash FROM app_user ORDER BY id",
				(rs, i) -> new StoredUser(rs.getLong("id"), rs.getString("username"), rs.getString("password_hash")));
	}

	public void updatePasswordHash(long id, String hash) {
		jdbc.update("UPDATE app_user SET password_hash = ? WHERE id = ?", hash, id);
	}
}
