package com.mimocodes.hrisrag.data.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A fully generated dataset held in memory as plain rows with explicit, deterministic IDs.
 * Generation is a pure function (spec → dataset), and writing is a separate step. Enum-like values are stored as the
 * exact strings the V2 CHECK constraints accept.
 */
public final class SeedDataset {

	public record DepartmentRow(long id, String code, String nameEn, String nameAr) {}

	public enum Level { HEAD, LEAD, STAFF, INTERN }

	public record EmployeeRow(long id, String code, String nameEn, String nameAr, String email, long departmentId,
			Long managerId, String jobTitle, String employmentType, LocalDate hireDate, String status, Level level,
			boolean anchor) {
		public boolean active() {
			return "ACTIVE".equals(status);
		}
	}

	public record CompensationRow(long employeeId, BigDecimal monthlySalary, String currency, LocalDate effectiveFrom) {}

	public record LeaveTypeRow(long id, String code, String nameEn, String nameAr, BigDecimal defaultAnnualDays,
			boolean hasBalance) {}

	public record LeaveBalanceRow(long id, long employeeId, long leaveTypeId, short year, BigDecimal entitledDays,
			BigDecimal usedDays, BigDecimal pendingDays) {}

	public record LeaveRequestRow(long id, String code, long employeeId, long leaveTypeId, LocalDate startDate,
			LocalDate endDate, BigDecimal days, String status, Long approverId, String reason, Instant submittedAt,
			Instant decidedAt) {}

	public record AttendanceRow(long id, long employeeId, LocalDate workDate, Instant checkIn, Instant checkOut,
			int minutesLate, String status) {}

	public record MeetingRow(long id, String code, String title, long organizerId, Instant startsAt, Instant endsAt,
			String location, boolean requiresApproval, String status) {}

	public record InvitationRow(long meetingId, long employeeId, String response, Boolean attended) {}

	public record RewardRow(long id, long employeeId, String rewardType, LocalDate awardedOn, Long awardedById,
			String description) {}

	public record DisciplinaryRow(long id, long employeeId, String actionType, LocalDate issuedOn, Long issuedById,
			String reason) {}

	public record AnnouncementRow(long id, String code, String title, String body, String language, String scope,
			Long departmentId, Instant publishedAt, Instant expiresAt) {}

	public final List<DepartmentRow> departments = new ArrayList<>();
	public final List<EmployeeRow> employees = new ArrayList<>();
	public final List<CompensationRow> compensations = new ArrayList<>();
	public final List<LeaveTypeRow> leaveTypes = new ArrayList<>();
	public final List<LeaveBalanceRow> leaveBalances = new ArrayList<>();
	public final List<LeaveRequestRow> leaveRequests = new ArrayList<>();
	public final List<AttendanceRow> attendance = new ArrayList<>();
	public final List<MeetingRow> meetings = new ArrayList<>();
	public final List<InvitationRow> invitations = new ArrayList<>();
	public final List<RewardRow> rewards = new ArrayList<>();
	public final List<DisciplinaryRow> disciplinary = new ArrayList<>();
	public final List<AnnouncementRow> announcements = new ArrayList<>();

	/** Row count per table, in insert order. Used for logging and write verification. */
	public Map<String, Integer> rowCounts() {
		Map<String, Integer> m = new LinkedHashMap<>();
		m.put("department", departments.size());
		m.put("employee", employees.size());
		m.put("employee_compensation", compensations.size());
		m.put("leave_type", leaveTypes.size());
		m.put("leave_balance", leaveBalances.size());
		m.put("leave_request", leaveRequests.size());
		m.put("attendance_record", attendance.size());
		m.put("meeting", meetings.size());
		m.put("meeting_invitation", invitations.size());
		m.put("employee_reward", rewards.size());
		m.put("disciplinary_record", disciplinary.size());
		m.put("announcement", announcements.size());
		return m;
	}

	public EmployeeRow employee(long id) {
		return employees.get((int) id - 1); // IDs are assigned 1..n in list order
	}

	public DepartmentRow department(String code) {
		return departments.stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow();
	}

	public EmployeeRow employee(String code) {
		return employees.stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
	}

	public LeaveTypeRow leaveType(String code) {
		return leaveTypes.stream().filter(t -> t.code().equals(code)).findFirst().orElseThrow();
	}
}
