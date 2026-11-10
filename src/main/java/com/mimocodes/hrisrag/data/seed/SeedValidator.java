package com.mimocodes.hrisrag.data.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Validates the seeded database (not the in-memory dataset): every rule is evaluated against what was actually
 * written. Used after every seed run, and directly by tests with deliberately corrupted data.
 */
@Component
public class SeedValidator {

	/** Minimum share of a target count that must be reached when generation uses retries (leave, rewards, ...). */
	static final double MIN_TARGET_SHARE = 0.9;

	private static final String ANCHOR_CODES = "('E-1000','E-1001','E-1002','E-1003','E-1004')";

	private final JdbcTemplate jdbc;

	public SeedValidator(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public record Report(Map<String, Long> tableCounts, List<String> failures) {
		public boolean ok() {
			return failures.isEmpty();
		}
	}

	public Report validate(SeedSpec spec, Map<String, Integer> expectedRowCounts) {
		List<String> failures = new ArrayList<>();
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : SeedWriter.TABLES_IN_INSERT_ORDER) {
			counts.put(table, count("SELECT count(*) FROM " + table));
		}

		checkCounts(spec, expectedRowCounts, counts, failures);
		checkOrganisation(failures);
		checkLeave(spec, failures);
		checkAttendance(failures);
		checkMeetings(failures);
		checkAnchorIsolation(failures);
		checkAnchorAnswers(failures);
		return new Report(counts, failures);
	}

	private void checkCounts(SeedSpec spec, Map<String, Integer> expected, Map<String, Long> actual, List<String> f) {
		expected.forEach((table, n) -> {
			if (actual.get(table) != n.longValue()) {
				f.add("count: " + table + " has " + actual.get(table) + " rows, generator produced " + n);
			}
		});
		SeedCounts c = spec.counts();
		exact(f, "department", actual, c.departments());
		exact(f, "employee", actual, c.employees());
		exact(f, "meeting", actual, c.meetings());
		exact(f, "announcement", actual, c.announcements());
		atLeastShare(f, "leave_request", actual, c.leaveRequests());
		atLeastShare(f, "employee_reward", actual, c.rewards());
		atLeastShare(f, "disciplinary_record", actual, c.disciplinaryRecords());
		if (actual.get("attendance_record") == 0) {
			f.add("count: attendance_record is empty");
		}
	}

	private void checkOrganisation(List<String> f) {
		zero(f, "organisation: manager in a different department", """
				SELECT count(*) FROM employee e JOIN employee m ON m.id = e.manager_id
				WHERE m.department_id <> e.department_id""");
		zero(f, "organisation: manager is not ACTIVE", """
				SELECT count(*) FROM employee e JOIN employee m ON m.id = e.manager_id WHERE m.status <> 'ACTIVE'""");
		zero(f, "organisation: employee without compensation", """
				SELECT count(*) FROM employee e LEFT JOIN employee_compensation c ON c.employee_id = e.id
				WHERE c.employee_id IS NULL""");
		zero(f, "organisation: non-synthetic email domain",
				"SELECT count(*) FROM employee WHERE email NOT LIKE '%@example.test'");
	}

	private void checkLeave(SeedSpec spec, List<String> f) {
		zero(f, "leave: balance used/pending differs from the sum of APPROVED/PENDING requests", """
				SELECT count(*) FROM leave_balance b
				LEFT JOIN (SELECT employee_id, leave_type_id, extract(year FROM start_date)::int AS y,
				                  sum(days) FILTER (WHERE status = 'APPROVED') AS approved,
				                  sum(days) FILTER (WHERE status = 'PENDING') AS pending
				           FROM leave_request GROUP BY 1, 2, 3) r
				  ON r.employee_id = b.employee_id AND r.leave_type_id = b.leave_type_id AND r.y = b.year
				WHERE b.used_days <> coalesce(r.approved, 0) OR b.pending_days <> coalesce(r.pending, 0)""");
		zero(f, "leave: used + pending exceeds entitlement",
				"SELECT count(*) FROM leave_balance WHERE used_days + pending_days > entitled_days");
		zero(f, "leave: APPROVED/PENDING requests that overlap for the same employee", """
				SELECT count(*) FROM leave_request a JOIN leave_request b
				  ON a.employee_id = b.employee_id AND a.id < b.id
				 AND a.start_date <= b.end_date AND b.start_date <= a.end_date
				WHERE a.status IN ('APPROVED','PENDING') AND b.status IN ('APPROVED','PENDING')""");
		zero(f, "leave: decided request approved by someone other than the manager (or the HR head for top-level staff)", """
				SELECT count(*) FROM leave_request r JOIN employee e ON e.id = r.employee_id
				WHERE r.status IN ('APPROVED','REJECTED')
				  AND r.approver_id IS DISTINCT FROM e.manager_id
				  AND NOT (e.manager_id IS NULL AND r.approver_id IN
				        (SELECT id FROM employee WHERE job_title = 'Head of Human Resources'))""");
		zero(f, "leave: submitted or decided after the reference date", """
				SELECT count(*) FROM leave_request WHERE submitted_at > ? OR decided_at > ?""",
				startOf(spec.referenceDate()), startOf(spec.referenceDate()));

		List<Map<String, Object>> requests = jdbc.queryForList("SELECT request_code, start_date, end_date, days FROM leave_request");
		long wrongDays = requests.stream().filter(r -> {
			LocalDate s = ((java.sql.Date) r.get("start_date")).toLocalDate();
			LocalDate e = ((java.sql.Date) r.get("end_date")).toLocalDate();
			return !WorkCalendar.isWorkday(s) || !WorkCalendar.isWorkday(e)
					|| ((BigDecimal) r.get("days")).compareTo(BigDecimal.valueOf(WorkCalendar.workdaysBetween(s, e))) != 0;
		}).count();
		if (wrongDays > 0) {
			f.add("leave: " + wrongDays + " requests whose days != workdays in range, or that start/end on a non-workday");
		}
	}

	private void checkAttendance(List<String> f) {
		zero(f, "attendance: record on a weekend (Friday/Saturday)",
				"SELECT count(*) FROM attendance_record WHERE extract(isodow FROM work_date) IN (5, 6)");
		long holidayRows = jdbc.queryForList("SELECT DISTINCT work_date FROM attendance_record", java.sql.Date.class)
				.stream().map(java.sql.Date::toLocalDate).filter(WorkCalendar::isHoliday).count();
		if (holidayRows > 0) {
			f.add("attendance: records on " + holidayRows + " public holiday date(s)");
		}
		zero(f, "attendance: worked day recorded during APPROVED leave", """
				SELECT count(*) FROM attendance_record a JOIN leave_request r
				  ON r.employee_id = a.employee_id AND r.status = 'APPROVED'
				 AND a.work_date BETWEEN r.start_date AND r.end_date
				WHERE a.status <> 'ON_LEAVE'""");
		zero(f, "attendance: ON_LEAVE without an APPROVED leave request", """
				SELECT count(*) FROM attendance_record a WHERE a.status = 'ON_LEAVE' AND NOT EXISTS (
				  SELECT 1 FROM leave_request r WHERE r.employee_id = a.employee_id AND r.status = 'APPROVED'
				    AND a.work_date BETWEEN r.start_date AND r.end_date)""");
		zero(f, "attendance: LATE status inconsistent with the grace period", """
				SELECT count(*) FROM attendance_record
				WHERE (status = 'LATE' AND minutes_late <= ?) OR (status IN ('PRESENT','REMOTE') AND minutes_late > ?)""",
				WorkCalendar.LATE_GRACE_MINUTES, WorkCalendar.LATE_GRACE_MINUTES);
		zero(f, "attendance: record before hire date or for a TERMINATED employee", """
				SELECT count(*) FROM attendance_record a JOIN employee e ON e.id = a.employee_id
				WHERE a.work_date < e.hire_date OR e.status = 'TERMINATED'""");
	}

	private void checkMeetings(List<String> f) {
		zero(f, "meetings: organizer not invited", """
				SELECT count(*) FROM meeting m WHERE NOT EXISTS (
				  SELECT 1 FROM meeting_invitation i WHERE i.meeting_id = m.id AND i.employee_id = m.organizer_id)""");
		zero(f, "meetings: PENDING_APPROVAL meeting that does not require approval",
				"SELECT count(*) FROM meeting WHERE status = 'PENDING_APPROVAL' AND NOT requires_approval");
		zero(f, "meetings: attendance recorded for a meeting that is not COMPLETED", """
				SELECT count(*) FROM meeting_invitation i JOIN meeting m ON m.id = i.meeting_id
				WHERE i.attended IS NOT NULL AND m.status <> 'COMPLETED'""");
	}

	private void checkAnchorIsolation(List<String> f) {
		zero(f, "anchors: random employee reports to an anchor", """
				SELECT count(*) FROM employee e JOIN employee m ON m.id = e.manager_id
				WHERE m.employee_code IN %s AND e.employee_code NOT IN %s""".formatted(ANCHOR_CODES, ANCHOR_CODES));
		zero(f, "anchors: non-anchor leave request for an anchor employee", """
				SELECT count(*) FROM leave_request r JOIN employee e ON e.id = r.employee_id
				WHERE e.employee_code IN %s AND r.request_code NOT LIKE 'LR-100_'""".formatted(ANCHOR_CODES));
		zero(f, "anchors: anchor employee invited to a random meeting", """
				SELECT count(*) FROM meeting_invitation i JOIN employee e ON e.id = i.employee_id
				JOIN meeting m ON m.id = i.meeting_id
				WHERE e.employee_code IN %s AND m.meeting_code NOT LIKE 'M-100_'""".formatted(ANCHOR_CODES));
	}

	private void checkAnchorAnswers(List<String> f) {
		expectDecimal(f, "anchor: " + AnchorScenario.EMPLOYEE + " ANNUAL remaining",
				AnchorScenario.EMPLOYEE_ANNUAL_REMAINING, remaining(AnchorScenario.EMPLOYEE, "ANNUAL"));
		expectDecimal(f, "anchor: " + AnchorScenario.EMPLOYEE + " SICK remaining",
				AnchorScenario.EMPLOYEE_SICK_REMAINING, remaining(AnchorScenario.EMPLOYEE, "SICK"));
		expectDecimal(f, "anchor: " + AnchorScenario.TEAMMATE + " ANNUAL remaining",
				AnchorScenario.TEAMMATE_ANNUAL_REMAINING, remaining(AnchorScenario.TEAMMATE, "ANNUAL"));
		expect(f, "anchor: " + AnchorScenario.PENDING_REQUEST + " status", "PENDING",
				requestStatus(AnchorScenario.PENDING_REQUEST));
		expect(f, "anchor: " + AnchorScenario.APPROVED_REQUEST + " status", "APPROVED",
				requestStatus(AnchorScenario.APPROVED_REQUEST));
		expect(f, "anchor: " + AnchorScenario.REJECTED_REQUEST + " status", "REJECTED",
				requestStatus(AnchorScenario.REJECTED_REQUEST));
		List<String> upcoming = jdbc.queryForList("""
				SELECT m.meeting_code FROM meeting_invitation i
				JOIN meeting m ON m.id = i.meeting_id JOIN employee e ON e.id = i.employee_id
				WHERE e.employee_code = ? AND m.starts_at > ? AND m.status <> 'CANCELLED' AND i.response <> 'DECLINED'
				ORDER BY m.starts_at""", String.class, AnchorScenario.EMPLOYEE, startOf(AnchorScenario.REFERENCE_DATE));
		expect(f, "anchor: upcoming meetings of " + AnchorScenario.EMPLOYEE, AnchorScenario.EMPLOYEE_UPCOMING_MEETINGS,
				upcoming);
		expect(f, "anchor: late days of " + AnchorScenario.EMPLOYEE + " in October 2026",
				(long) AnchorScenario.EMPLOYEE_LATE_DAYS_OCTOBER_2026, count("""
						SELECT count(*) FROM attendance_record a JOIN employee e ON e.id = a.employee_id
						WHERE e.employee_code = ? AND a.status = 'LATE'
						  AND a.work_date BETWEEN '2026-10-01' AND '2026-10-31'""", AnchorScenario.EMPLOYEE));
		expectDecimal(f, "anchor: " + AnchorScenario.EMPLOYEE + " monthly salary", AnchorScenario.EMPLOYEE_MONTHLY_SALARY,
				jdbc.queryForObject("""
						SELECT c.monthly_salary FROM employee_compensation c JOIN employee e ON e.id = c.employee_id
						WHERE e.employee_code = ?""", BigDecimal.class, AnchorScenario.EMPLOYEE));
	}

	// ---- helpers ------------------------------------------------------------------------------------------------

	private BigDecimal remaining(String employeeCode, String leaveType) {
		List<BigDecimal> r = jdbc.queryForList("""
				SELECT b.entitled_days - b.used_days - b.pending_days FROM leave_balance b
				JOIN employee e ON e.id = b.employee_id JOIN leave_type t ON t.id = b.leave_type_id
				WHERE e.employee_code = ? AND t.code = ? AND b.year = ?""", BigDecimal.class, employeeCode, leaveType,
				AnchorScenario.LEAVE_YEAR);
		return r.isEmpty() ? null : r.getFirst();
	}

	private String requestStatus(String code) {
		List<String> r = jdbc.queryForList("SELECT status FROM leave_request WHERE request_code = ?", String.class, code);
		return r.isEmpty() ? null : r.getFirst();
	}

	/** Start of the reference date in company time: nothing may be submitted or decided after it. */
	private static java.sql.Timestamp startOf(LocalDate day) {
		return java.sql.Timestamp.from(day.atStartOfDay(WorkCalendar.ZONE).toInstant());
	}

	private long count(String sql, Object... args) {
		Long n = jdbc.queryForObject(sql, Long.class, args);
		return n == null ? 0 : n;
	}

	private void zero(List<String> f, String rule, String sql, Object... args) {
		long n = count(sql, args);
		if (n != 0) {
			f.add(rule + " (" + n + " rows)");
		}
	}

	private static void exact(List<String> f, String table, Map<String, Long> actual, int target) {
		if (actual.get(table) != target) {
			f.add("count: " + table + " = " + actual.get(table) + ", target " + target);
		}
	}

	private static void atLeastShare(List<String> f, String table, Map<String, Long> actual, int target) {
		if (actual.get(table) < Math.floor(target * MIN_TARGET_SHARE)) {
			f.add("count: " + table + " = " + actual.get(table) + ", below " + (int) (MIN_TARGET_SHARE * 100)
					+ "% of target " + target);
		}
	}

	private static void expect(List<String> f, String what, Object expected, Object actual) {
		if (!expected.equals(actual)) {
			f.add(what + ": expected " + expected + " but was " + actual);
		}
	}

	private static void expectDecimal(List<String> f, String what, BigDecimal expected, BigDecimal actual) {
		if (actual == null || expected.compareTo(actual) != 0) {
			f.add(what + ": expected " + expected + " but was " + actual);
		}
	}
}
