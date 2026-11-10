package com.mimocodes.hrisrag.data.seed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Command-line reseed, active only with {@code --hrisrag.seed.run=true}. Seeds, prints a summary and exits
 * (code 0 on success, 1 on failure). It never runs on a normal application start.
 */
@Component
@ConditionalOnProperty(prefix = "hrisrag.seed", name = "run", havingValue = "true")
public class SeedRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SeedRunner.class);

	private final SeedService seedService;
	private final DatasetFingerprint fingerprint;
	private final SeedProperties properties;
	private final ConfigurableApplicationContext context;

	public SeedRunner(SeedService seedService, DatasetFingerprint fingerprint, SeedProperties properties,
			ConfigurableApplicationContext context) {
		this.seedService = seedService;
		this.fingerprint = fingerprint;
		this.properties = properties;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		int exitCode = 0;
		try {
			SeedService.Result r = seedService.reseed(properties.toSpec());
			StringBuilder sb = new StringBuilder("\n===== SEED SUMMARY =====\n");
			sb.append("profile=").append(r.spec().profile()).append(" seed=").append(r.spec().seed())
					.append(" referenceDate=").append(r.spec().referenceDate()).append('\n');
			r.rowCounts().forEach((table, n) -> sb.append(String.format("  %-22s %,10d rows  %,7d ms%n", table, n,
					r.writeMs().get(table))));
			sb.append(String.format("generate=%,d ms  write=%,d ms  validate=%,d ms%n", r.generateMs(), r.totalWriteMs(),
					r.validateMs()));
			sb.append("validation: PASSED (").append(r.report().failures().size()).append(" failures)\n");
			sb.append("fingerprint=").append(fingerprint.combined()).append('\n');
			log.info(sb.toString());
		}
		catch (RuntimeException e) {
			log.error("Seeding failed: {}", e.getMessage());
			exitCode = 1;
		}
		int code = exitCode;
		System.exit(SpringApplication.exit(context, () -> code));
	}
}
