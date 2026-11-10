package com.mimocodes.hrisrag.data.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.mimocodes.hrisrag.IntegrationTestBase;

/**
 * Proves the validator can actually catch problems: seed valid data, corrupt it, and expect a specific failure.
 * Each test runs in one transaction (seed + corruption + validation) that is rolled back afterwards.
 */
@Transactional
class SeedValidatorTest extends IntegrationTestBase {

	@Autowired SeedService seedService;
	@Autowired SeedValidator validator;
	@Autowired JdbcTemplate jdbc;

	private SeedSpec spec;
	private SeedService.Result seeded;

	private List<String> corruptAndValidate(String... statements) {
		spec = SeedSpec.of(42, SeedProfile.TEST);
		seeded = seedService.reseed(spec);
		assertThat(seeded.report().failures()).as("baseline must be valid").isEmpty();
		for (String sql : statements) {
			jdbc.update(sql);
		}
		return validator.validate(spec, seeded.rowCounts()).failures();
	}

	@Test
	void detectsBalanceInconsistentWithRequests() {
		List<String> failures = corruptAndValidate("""
				UPDATE leave_balance SET used_days = used_days + 1
				WHERE id = (SELECT max(id) FROM leave_balance WHERE entitled_days - used_days - pending_days >= 1)""");

		assertThat(failures).anyMatch(f -> f.startsWith("leave: balance used/pending differs"));
	}

	@Test
	void detectsOverlappingLeave() {
		List<String> failures = corruptAndValidate("""
				INSERT INTO leave_request (request_code, employee_id, leave_type_id, start_date, end_date, days, status,
				  submitted_at)
				SELECT 'LR-9999', employee_id, leave_type_id, start_date, start_date, 1, 'PENDING', submitted_at
				FROM leave_request WHERE request_code = 'LR-1000'""");

		assertThat(failures).anyMatch(f -> f.startsWith("leave: APPROVED/PENDING requests that overlap"));
	}

	@Test
	void detectsAttendanceOnWeekend() {
		List<String> failures = corruptAndValidate("""
				UPDATE attendance_record SET work_date = DATE '2026-10-30'
				WHERE id = (SELECT max(id) FROM attendance_record WHERE status = 'PRESENT')""");

		assertThat(failures).anyMatch(f -> f.startsWith("attendance: record on a weekend"));
	}

	@Test
	void detectsChangedAnchorAnswer() {
		List<String> failures = corruptAndValidate(
				"UPDATE leave_request SET status = 'CANCELLED' WHERE request_code = 'LR-1005'");

		assertThat(failures).anyMatch(f -> f.startsWith("anchor: LR-1005 status: expected PENDING"));
	}

	@Test
	void detectsMissingRows() {
		List<String> failures = corruptAndValidate(
				"DELETE FROM announcement WHERE id = (SELECT max(id) FROM announcement)");

		assertThat(failures).anyMatch(f -> f.startsWith("count: announcement"));
	}
}
