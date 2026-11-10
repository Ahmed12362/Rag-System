package com.mimocodes.hrisrag.data.seed;

import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.seed.*}. Seeding only happens when {@code run=true} is passed explicitly; it never runs on a normal start.
 *
 * @param run           when true, the app truncates the HRIS tables, generates, validates, then exits
 * @param profile       TEST, STANDARD or LARGE
 * @param seed          master random seed
 * @param referenceDate the dataset's "today"
 * @param overrides     optional per-count overrides on top of the profile
 */
@ConfigurationProperties(prefix = "hrisrag.seed")
public record SeedProperties(
		@DefaultValue("false") boolean run,
		@DefaultValue("STANDARD") SeedProfile profile,
		@DefaultValue("42") long seed,
		@DefaultValue("2026-11-01") LocalDate referenceDate,
		Overrides overrides) {

	public record Overrides(Integer departments, Integer employees, Integer attendanceWorkdays, Integer leaveRequests,
			Integer meetings, Integer rewards, Integer disciplinaryRecords, Integer announcements) {
	}

	public SeedSpec toSpec() {
		return new SeedSpec(seed, profile, referenceDate, profile.counts().withOverrides(overrides));
	}
}
