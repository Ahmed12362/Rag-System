package com.mimocodes.hrisrag.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * At startup, sets each simulated user's BCrypt hash from {@code APP_DEMO_PASSWORD}. Hashes are only rewritten when
 * missing or when the configured password changed, so restarts are cheap. The password itself is never logged or
 * stored; without it, the users stay locked (NULL hash).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoUserProvisioner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoUserProvisioner.class);

	private final AppUserStore users;
	private final PasswordEncoder encoder;
	private final SecurityProperties props;

	public DemoUserProvisioner(AppUserStore users, PasswordEncoder encoder, SecurityProperties props) {
		this.users = users;
		this.encoder = encoder;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		String password = props.demoPassword();
		if (password == null || password.isBlank()) {
			log.warn("APP_DEMO_PASSWORD is not set: the demo users cannot log in (set it in .env)");
			return;
		}
		if (password.length() < 8) {
			log.warn("APP_DEMO_PASSWORD is shorter than 8 characters: the demo users stay locked");
			return;
		}
		int updated = 0, total = 0;
		for (AppUserStore.StoredUser u : users.all()) {
			total++;
			if (u.passwordHash() == null || !encoder.matches(password, u.passwordHash())) {
				users.updatePasswordHash(u.id(), encoder.encode(password));
				updated++;
			}
		}
		log.info("demo users ready: {} user(s), {} password hash(es) updated", total, updated);
	}
}
