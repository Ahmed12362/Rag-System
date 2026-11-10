package com.mimocodes.hrisrag.data.seed;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.stereotype.Component;

/**
 * Writes a {@link SeedDataset} with JDBC batch inserts (JPA IDENTITY generation would force row-by-row inserts, far
 * too slow for the LARGE profile). IDs are explicit and deterministic; sequences are advanced afterwards so later JPA
 * inserts do not collide. Must run inside the caller's transaction.
 */
@Component
public class SeedWriter {

	static final List<String> TABLES_IN_INSERT_ORDER = List.of(
			"department", "employee", "employee_compensation", "leave_type", "leave_balance", "leave_request",
			"attendance_record", "meeting", "meeting_invitation", "employee_reward", "disciplinary_record",
			"announcement");

	private static final List<String> TABLES_WITH_ID_SEQUENCE = List.of(
			"department", "employee", "leave_type", "leave_balance", "leave_request", "attendance_record", "meeting",
			"employee_reward", "disciplinary_record", "announcement");

	private static final int BATCH_SIZE = 5_000;

	private final JdbcTemplate jdbc;

	public SeedWriter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Removes all HRIS rows and resets identities. */
	public void truncate() {
		jdbc.execute("TRUNCATE TABLE " + String.join(", ", TABLES_IN_INSERT_ORDER.reversed()) + " RESTART IDENTITY CASCADE");
	}

	/** Inserts every table and returns the elapsed milliseconds per table. */
	public Map<String, Long> write(SeedDataset ds) {
		Map<String, Long> timings = new LinkedHashMap<>();
		timed(timings, "department", () -> insert(
				"INSERT INTO department (id, code, name_en, name_ar) VALUES (?, ?, ?, ?)", ds.departments, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setString(2, r.code());
					ps.setString(3, r.nameEn());
					ps.setString(4, r.nameAr());
				}));
		timed(timings, "employee", () -> insert("""
				INSERT INTO employee (id, employee_code, full_name_en, full_name_ar, email, department_id, manager_id,
				  job_title, employment_type, hire_date, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				ds.employees, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setString(2, r.code());
					ps.setString(3, r.nameEn());
					ps.setString(4, r.nameAr());
					ps.setString(5, r.email());
					ps.setLong(6, r.departmentId());
					setLong(ps, 7, r.managerId());
					ps.setString(8, r.jobTitle());
					ps.setString(9, r.employmentType());
					ps.setObject(10, r.hireDate());
					ps.setString(11, r.status());
				}));
		timed(timings, "employee_compensation", () -> insert("""
				INSERT INTO employee_compensation (employee_id, monthly_salary, currency, effective_from)
				VALUES (?, ?, ?, ?)""", ds.compensations, (ps, r) -> {
					ps.setLong(1, r.employeeId());
					ps.setBigDecimal(2, r.monthlySalary());
					ps.setString(3, r.currency());
					ps.setObject(4, r.effectiveFrom());
				}));
		timed(timings, "leave_type", () -> insert("""
				INSERT INTO leave_type (id, code, name_en, name_ar, default_annual_days) VALUES (?, ?, ?, ?, ?)""",
				ds.leaveTypes, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setString(2, r.code());
					ps.setString(3, r.nameEn());
					ps.setString(4, r.nameAr());
					ps.setBigDecimal(5, r.defaultAnnualDays());
				}));
		timed(timings, "leave_balance", () -> insert("""
				INSERT INTO leave_balance (id, employee_id, leave_type_id, year, entitled_days, used_days, pending_days)
				VALUES (?, ?, ?, ?, ?, ?, ?)""", ds.leaveBalances, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setLong(2, r.employeeId());
					ps.setLong(3, r.leaveTypeId());
					ps.setShort(4, r.year());
					ps.setBigDecimal(5, r.entitledDays());
					ps.setBigDecimal(6, r.usedDays());
					ps.setBigDecimal(7, r.pendingDays());
				}));
		timed(timings, "leave_request", () -> insert("""
				INSERT INTO leave_request (id, request_code, employee_id, leave_type_id, start_date, end_date, days, status,
				  approver_id, reason, submitted_at, decided_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
				ds.leaveRequests, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setString(2, r.code());
					ps.setLong(3, r.employeeId());
					ps.setLong(4, r.leaveTypeId());
					ps.setObject(5, r.startDate());
					ps.setObject(6, r.endDate());
					ps.setBigDecimal(7, r.days());
					ps.setString(8, r.status());
					setLong(ps, 9, r.approverId());
					ps.setString(10, r.reason());
					setInstant(ps, 11, r.submittedAt());
					setInstant(ps, 12, r.decidedAt());
				}));
		timed(timings, "attendance_record", () -> insert("""
				INSERT INTO attendance_record (id, employee_id, work_date, check_in, check_out, minutes_late, status)
				VALUES (?, ?, ?, ?, ?, ?, ?)""", ds.attendance, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setLong(2, r.employeeId());
					ps.setObject(3, r.workDate());
					setInstant(ps, 4, r.checkIn());
					setInstant(ps, 5, r.checkOut());
					ps.setInt(6, r.minutesLate());
					ps.setString(7, r.status());
				}));
		timed(timings, "meeting", () -> insert("""
				INSERT INTO meeting (id, meeting_code, title, organizer_id, starts_at, ends_at, location, requires_approval,
				  status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", ds.meetings, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setString(2, r.code());
					ps.setString(3, r.title());
					ps.setLong(4, r.organizerId());
					setInstant(ps, 5, r.startsAt());
					setInstant(ps, 6, r.endsAt());
					ps.setString(7, r.location());
					ps.setBoolean(8, r.requiresApproval());
					ps.setString(9, r.status());
				}));
		timed(timings, "meeting_invitation", () -> insert("""
				INSERT INTO meeting_invitation (meeting_id, employee_id, response, attended) VALUES (?, ?, ?, ?)""",
				ds.invitations, (ps, r) -> {
					ps.setLong(1, r.meetingId());
					ps.setLong(2, r.employeeId());
					ps.setString(3, r.response());
					if (r.attended() == null) {
						ps.setNull(4, Types.BOOLEAN);
					} else {
						ps.setBoolean(4, r.attended());
					}
				}));
		timed(timings, "employee_reward", () -> insert("""
				INSERT INTO employee_reward (id, employee_id, reward_type, awarded_on, awarded_by_id, description)
				VALUES (?, ?, ?, ?, ?, ?)""", ds.rewards, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setLong(2, r.employeeId());
					ps.setString(3, r.rewardType());
					ps.setObject(4, r.awardedOn());
					setLong(ps, 5, r.awardedById());
					ps.setString(6, r.description());
				}));
		timed(timings, "disciplinary_record", () -> insert("""
				INSERT INTO disciplinary_record (id, employee_id, action_type, issued_on, issued_by_id, reason)
				VALUES (?, ?, ?, ?, ?, ?)""", ds.disciplinary, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setLong(2, r.employeeId());
					ps.setString(3, r.actionType());
					ps.setObject(4, r.issuedOn());
					setLong(ps, 5, r.issuedById());
					ps.setString(6, r.reason());
				}));
		timed(timings, "announcement", () -> insert("""
				INSERT INTO announcement (id, announcement_code, title, body, language, scope, department_id, published_at,
				  expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", ds.announcements, (ps, r) -> {
					ps.setLong(1, r.id());
					ps.setString(2, r.code());
					ps.setString(3, r.title());
					ps.setString(4, r.body());
					ps.setString(5, r.language());
					ps.setString(6, r.scope());
					setLong(ps, 7, r.departmentId());
					setInstant(ps, 8, r.publishedAt());
					setInstant(ps, 9, r.expiresAt());
				}));
		advanceSequences();
		return timings;
	}

	private void advanceSequences() {
		for (String table : TABLES_WITH_ID_SEQUENCE) {
			jdbc.execute("SELECT setval(pg_get_serial_sequence('" + table + "', 'id'), "
					+ "COALESCE((SELECT MAX(id) FROM " + table + "), 1), (SELECT COUNT(*) > 0 FROM " + table + "))");
		}
	}

	private <T> void insert(String sql, List<T> rows, ParameterizedPreparedStatementSetter<T> setter) {
		if (!rows.isEmpty()) {
			jdbc.batchUpdate(sql, rows, BATCH_SIZE, setter);
		}
	}

	private static void timed(Map<String, Long> timings, String table, Runnable action) {
		long start = System.nanoTime();
		action.run();
		timings.put(table, (System.nanoTime() - start) / 1_000_000);
	}

	private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.BIGINT);
		} else {
			ps.setLong(index, value);
		}
	}

	private static void setInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
		} else {
			ps.setObject(index, OffsetDateTime.ofInstant(value, ZoneOffset.UTC));
		}
	}
}
