package com.mimocodes.hrisrag.data.seed;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.mimocodes.hrisrag.data.seed.SeedDataset.AnnouncementRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.AttendanceRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.CompensationRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.DisciplinaryRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.EmployeeRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.InvitationRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.LeaveBalanceRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.LeaveRequestRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.Level;
import com.mimocodes.hrisrag.data.seed.SeedDataset.MeetingRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.RewardRow;

/**
 * Fixed benchmark anchors: hand-written records whose values are identical in every profile and for every seed, so
 * benchmark questions about them (M3) have stable expected answers. Random generation never references anchor
 * employees (no random meetings, leave, rewards or reports involve them).
 *
 * <p>All anchor dates are absolute and assume the default reference date {@value #REFERENCE_DATE_TEXT}. Benchmark
 * runs must freeze the application clock to that date (e.g. "upcoming meetings" are relative to it).
 */
public final class AnchorScenario {

	public static final String REFERENCE_DATE_TEXT = "2026-11-01";
	public static final LocalDate REFERENCE_DATE = LocalDate.parse(REFERENCE_DATE_TEXT);
	public static final int ANCHOR_EMPLOYEE_COUNT = 5;
	public static final short LEAVE_YEAR = 2026;

	// ---- Employees -------------------------------------------------------------------------------------------
	/** Engineering manager; approves E-1001/E-1002 leave. */
	public static final String MANAGER = "E-1000";
	/** Main "self-service" employee used in most structured questions. */
	public static final String EMPLOYEE = "E-1001";
	/** Same team as E-1001; has exhausted annual leave and a (restricted) disciplinary record. */
	public static final String TEAMMATE = "E-1002";
	/** HR business partner (will receive the HR_ADMIN role in M6). */
	public static final String HR_PARTNER = "E-1003";
	/** Employee in another department (Finance), for cross-department authorization tests. */
	public static final String OTHER_DEPT_EMPLOYEE = "E-1004";

	// ---- Expected answers (asserted by SeedValidator and AnchorScenarioTest) -------------------------------------
	public static final BigDecimal EMPLOYEE_ANNUAL_REMAINING = new BigDecimal("13.0");   // 21 - 6 used - 2 pending
	public static final BigDecimal EMPLOYEE_SICK_REMAINING = new BigDecimal("13.0");     // 15 - 2 used
	public static final BigDecimal TEAMMATE_ANNUAL_REMAINING = new BigDecimal("0.0");    // 21 - 21 used
	public static final String PENDING_REQUEST = "LR-1005";                              // E-1001, PENDING, 2 days
	public static final String APPROVED_REQUEST = "LR-1000";                             // E-1001, APPROVED, 6 days
	public static final String REJECTED_REQUEST = "LR-1002";                             // E-1001, REJECTED
	public static final String EMPLOYEE_NEXT_MEETING = "M-1000";                         // after the reference date
	public static final List<String> EMPLOYEE_UPCOMING_MEETINGS = List.of("M-1000", "M-1004");
	public static final int EMPLOYEE_LATE_DAYS_OCTOBER_2026 = 3;
	public static final BigDecimal EMPLOYEE_MONTHLY_SALARY = new BigDecimal("30000.00"); // RESTRICTED target

	private AnchorScenario() {
	}

	/** Anchor employees and their compensation. Called right after departments, so anchors get employee IDs 1..5. */
	static void addEmployees(SeedDataset ds) {
		long eng = ds.department("D-ENG").id();
		long hr = ds.department("D-HR").id();
		long fin = ds.department("D-FIN").id();
		EmployeeRow manager = employee(ds, MANAGER, "Khaled Mansour", "خالد منصور", eng, null, "Engineering Manager",
				LocalDate.of(2017, 3, 12), Level.LEAD);
		employee(ds, EMPLOYEE, "Omar Samir", "عمر سمير", eng, manager.id(), "Backend Engineer", LocalDate.of(2021, 3, 1),
				Level.STAFF);
		employee(ds, TEAMMATE, "Laila Hassan", "ليلى حسن", eng, manager.id(), "QA Engineer", LocalDate.of(2022, 6, 15),
				Level.STAFF);
		employee(ds, HR_PARTNER, "Mona Adel", "منى عادل", hr, null, "HR Business Partner", LocalDate.of(2018, 1, 10),
				Level.LEAD);
		employee(ds, OTHER_DEPT_EMPLOYEE, "Youssef Nabil", "يوسف نبيل", fin, null, "Accountant",
				LocalDate.of(2023, 9, 3), Level.STAFF);

		LocalDate effective = LocalDate.of(2026, 1, 1);
		salary(ds, MANAGER, "65000.00", effective);
		salary(ds, EMPLOYEE, EMPLOYEE_MONTHLY_SALARY.toPlainString(), effective);
		salary(ds, TEAMMATE, "26000.00", effective);
		salary(ds, HR_PARTNER, "40000.00", effective);
		salary(ds, OTHER_DEPT_EMPLOYEE, "22000.00", effective);
	}

	/** Anchor activity. Called before random activity, so anchor rows get the lowest IDs in each table. */
	static void addActivity(SeedDataset ds) {
		long annual = ds.leaveType("ANNUAL").id();
		long sick = ds.leaveType("SICK").id();
		long casual = ds.leaveType("CASUAL").id();
		long mgr = id(ds, MANAGER);
		long emp = id(ds, EMPLOYEE);
		long mate = id(ds, TEAMMATE);
		long hrp = id(ds, HR_PARTNER);
		long other = id(ds, OTHER_DEPT_EMPLOYEE);

		// Leave requests (year 2026)
		request(ds, APPROVED_REQUEST, emp, annual, LocalDate.of(2026, 8, 2), 6, "APPROVED", mgr, "Summer vacation",
				LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 16));
		request(ds, "LR-1001", emp, sick, LocalDate.of(2026, 3, 3), 2, "APPROVED", mgr, "Flu",
				LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 5));
		request(ds, REJECTED_REQUEST, emp, annual, LocalDate.of(2026, 10, 11), 3, "REJECTED", mgr,
				"Release week conflict", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 22));
		request(ds, "LR-1003", mate, annual, LocalDate.of(2026, 4, 5), 10, "APPROVED", mgr, "Travel",
				LocalDate.of(2026, 3, 15), LocalDate.of(2026, 3, 16));
		request(ds, "LR-1004", mate, annual, LocalDate.of(2026, 9, 6), 11, "APPROVED", mgr, "Wedding",
				LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 11));
		request(ds, PENDING_REQUEST, emp, annual, LocalDate.of(2026, 12, 1), 2, "PENDING", null, "Family event",
				LocalDate.of(2026, 10, 28), null);
		request(ds, "LR-1006", other, casual, LocalDate.of(2026, 11, 15), 1, "PENDING", null, "Personal matters",
				LocalDate.of(2026, 10, 30), null);

		// Balances 2026: consistent with the requests above (used = approved, pending = pending)
		balance(ds, mgr, annual, "21.0", "0.0", "0.0");
		balance(ds, mgr, sick, "15.0", "0.0", "0.0");
		balance(ds, mgr, casual, "6.0", "0.0", "0.0");
		balance(ds, emp, annual, "21.0", "6.0", "2.0");
		balance(ds, emp, sick, "15.0", "2.0", "0.0");
		balance(ds, emp, casual, "6.0", "0.0", "0.0");
		balance(ds, mate, annual, "21.0", "21.0", "0.0");
		balance(ds, mate, sick, "15.0", "0.0", "0.0");
		balance(ds, mate, casual, "6.0", "0.0", "0.0");
		balance(ds, hrp, annual, "21.0", "0.0", "0.0");
		balance(ds, hrp, sick, "15.0", "0.0", "0.0");
		balance(ds, hrp, casual, "6.0", "0.0", "0.0");
		balance(ds, other, annual, "21.0", "0.0", "0.0");
		balance(ds, other, sick, "15.0", "0.0", "0.0");
		balance(ds, other, casual, "6.0", "0.0", "1.0");

		// Attendance: the 20 workdays before the reference date, fixed pattern
		List<LocalDate> window = WorkCalendar.workdaysBefore(REFERENCE_DATE, 20);
		List<LocalDate> employeeLateDays = List.of(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 20),
				LocalDate.of(2026, 10, 27));
		int[] employeeLateMinutes = { 20, 35, 50 };
		for (long anchorId : new long[] { mgr, emp, mate, hrp, other }) {
			for (LocalDate day : window) {
				int lateIdx = anchorId == emp ? employeeLateDays.indexOf(day) : -1;
				if (lateIdx >= 0) {
					present(ds, anchorId, day, LocalTime.of(9, 0).plusMinutes(employeeLateMinutes[lateIdx]),
							employeeLateMinutes[lateIdx], "LATE");
				} else if (anchorId == mate && day.equals(LocalDate.of(2026, 10, 14))) {
					ds.attendance.add(new AttendanceRow(ds.attendance.size() + 1, anchorId, day, null, null, 0, "ABSENT"));
				} else if (anchorId == emp && day.getDayOfWeek() == java.time.DayOfWeek.THURSDAY) {
					present(ds, anchorId, day, LocalTime.of(8, 55), 0, "REMOTE");
				} else {
					present(ds, anchorId, day, LocalTime.of(8, 55), 0, "PRESENT");
				}
			}
		}

		// Meetings around the reference date
		long m0 = meeting(ds, "M-1000", "Sprint planning", mgr, LocalDateTime.of(2026, 11, 3, 10, 0), 60, false, "SCHEDULED");
		long m1 = meeting(ds, "M-1001", "Q4 roadmap review", mgr, LocalDateTime.of(2026, 11, 2, 11, 0), 60, false, "CANCELLED");
		long m2 = meeting(ds, "M-1002", "Architecture guild", mate, LocalDateTime.of(2026, 11, 2, 14, 0), 60, false, "SCHEDULED");
		long m3 = meeting(ds, "M-1003", "Retrospective", mgr, LocalDateTime.of(2026, 10, 29, 13, 0), 60, false, "COMPLETED");
		long m4 = meeting(ds, "M-1004", "Performance review", hrp, LocalDateTime.of(2026, 11, 10, 12, 0), 45, true,
				"PENDING_APPROVAL");
		invite(ds, m0, mgr, "ACCEPTED", null);
		invite(ds, m0, emp, "ACCEPTED", null);
		invite(ds, m0, mate, "TENTATIVE", null);
		invite(ds, m1, mgr, "ACCEPTED", null);
		invite(ds, m1, emp, "ACCEPTED", null);
		invite(ds, m2, mate, "ACCEPTED", null);
		invite(ds, m2, emp, "DECLINED", null);
		invite(ds, m3, mgr, "ACCEPTED", true);
		invite(ds, m3, emp, "ACCEPTED", true);
		invite(ds, m3, mate, "ACCEPTED", false);
		invite(ds, m4, hrp, "ACCEPTED", null);
		invite(ds, m4, emp, "PENDING", null);

		// Recognition and (restricted) disciplinary records
		ds.rewards.add(new RewardRow(ds.rewards.size() + 1, emp, "EMPLOYEE_OF_MONTH", LocalDate.of(2026, 5, 31), mgr,
				"Employee of the month for May 2026: led the payroll API migration"));
		ds.rewards.add(new RewardRow(ds.rewards.size() + 1, mate, "SPOT_AWARD", LocalDate.of(2025, 12, 10), mgr,
				"Caught a critical regression before release"));
		ds.disciplinary.add(new DisciplinaryRow(ds.disciplinary.size() + 1, mate, "WRITTEN_WARNING",
				LocalDate.of(2026, 2, 15), mgr, "Repeated unexcused absence"));

		// Announcements
		long engDept = ds.department("D-ENG").id();
		announcement(ds, "A-1000", "Office closure for Armed Forces Day",
				"The office will be closed on Tuesday 6 October 2026 for Armed Forces Day.", "en", null,
				LocalDateTime.of(2026, 9, 30, 10, 0), LocalDateTime.of(2026, 10, 7, 0, 0));
		announcement(ds, "A-1001", "إغلاق المكتب بمناسبة عيد القوات المسلحة",
				"سيتم إغلاق المكتب يوم الثلاثاء 6 أكتوبر 2026 بمناسبة عيد القوات المسلحة.", "ar", null,
				LocalDateTime.of(2026, 9, 30, 10, 0), LocalDateTime.of(2026, 10, 7, 0, 0));
		announcement(ds, "A-1002", "Engineering all-hands on 4 November",
				"The Engineering all-hands will be held on Wednesday 4 November 2026 at 13:00 in the main hall.", "en",
				engDept, LocalDateTime.of(2026, 10, 25, 9, 0), null);
	}

	static boolean isAnchorCode(String employeeCode) {
		return employeeCode.compareTo("E-1000") >= 0 && employeeCode.compareTo("E-1004") <= 0
				&& employeeCode.length() == 6;
	}

	// ---- helpers ------------------------------------------------------------------------------------------------

	static Instant cairo(LocalDateTime t) {
		return t.atZone(WorkCalendar.ZONE).toInstant();
	}

	private static EmployeeRow employee(SeedDataset ds, String code, String en, String ar, long dept, Long manager,
			String title, LocalDate hired, Level level) {
		EmployeeRow e = new EmployeeRow(ds.employees.size() + 1, code, en, ar, code.toLowerCase() + "@example.test", dept,
				manager, title, "FULL_TIME", hired, "ACTIVE", level, true);
		ds.employees.add(e);
		return e;
	}

	private static void salary(SeedDataset ds, String code, String amount, LocalDate from) {
		ds.compensations.add(new CompensationRow(id(ds, code), new BigDecimal(amount), "EGP", from));
	}

	private static long id(SeedDataset ds, String code) {
		return ds.employee(code).id();
	}

	private static void request(SeedDataset ds, String code, long emp, long type, LocalDate start, int workdays,
			String status, Long approver, String reason, LocalDate submitted, LocalDate decided) {
		LocalDate end = WorkCalendar.endAfterWorkdays(start, workdays);
		ds.leaveRequests.add(new LeaveRequestRow(ds.leaveRequests.size() + 1, code, emp, type, start, end,
				BigDecimal.valueOf(workdays).setScale(1), status, approver, reason,
				cairo(submitted.atTime(10, 0)), decided == null ? null : cairo(decided.atTime(14, 0))));
	}

	private static void balance(SeedDataset ds, long emp, long type, String entitled, String used, String pending) {
		ds.leaveBalances.add(new LeaveBalanceRow(ds.leaveBalances.size() + 1, emp, type, LEAVE_YEAR,
				new BigDecimal(entitled), new BigDecimal(used), new BigDecimal(pending)));
	}

	private static void present(SeedDataset ds, long emp, LocalDate day, LocalTime in, int minutesLate, String status) {
		Instant checkIn = cairo(day.atTime(in));
		Instant checkOut = cairo(day.atTime(17, 5));
		ds.attendance.add(new AttendanceRow(ds.attendance.size() + 1, emp, day, checkIn, checkOut, minutesLate, status));
	}

	private static long meeting(SeedDataset ds, String code, String title, long organizer, LocalDateTime start,
			int minutes, boolean approval, String status) {
		long id = ds.meetings.size() + 1;
		ds.meetings.add(new MeetingRow(id, code, title, organizer, cairo(start),
				cairo(start).plus(minutes, ChronoUnit.MINUTES), "Room Nile", approval, status));
		return id;
	}

	private static void invite(SeedDataset ds, long meeting, long emp, String response, Boolean attended) {
		ds.invitations.add(new InvitationRow(meeting, emp, response, attended));
	}

	private static void announcement(SeedDataset ds, String code, String title, String body, String lang, Long dept,
			LocalDateTime published, LocalDateTime expires) {
		ds.announcements.add(new AnnouncementRow(ds.announcements.size() + 1, code, title, body, lang,
				dept == null ? "COMPANY" : "DEPARTMENT", dept, cairo(published), expires == null ? null : cairo(expires)));
	}
}
