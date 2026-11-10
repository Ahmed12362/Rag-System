package com.mimocodes.hrisrag.data.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import org.springframework.stereotype.Component;

import com.mimocodes.hrisrag.data.seed.ReferenceData.Bilingual;
import com.mimocodes.hrisrag.data.seed.ReferenceData.Dept;
import com.mimocodes.hrisrag.data.seed.ReferenceData.Name;
import com.mimocodes.hrisrag.data.seed.SeedDataset.AnnouncementRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.AttendanceRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.CompensationRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.DepartmentRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.DisciplinaryRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.EmployeeRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.InvitationRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.LeaveBalanceRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.LeaveRequestRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.LeaveTypeRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.Level;
import com.mimocodes.hrisrag.data.seed.SeedDataset.MeetingRow;
import com.mimocodes.hrisrag.data.seed.SeedDataset.RewardRow;

/**
 * Deterministic synthetic HRIS data: {@code generate(spec)} is a pure function, so the same spec always yields the same
 * rows. Each table draws from its own random stream derived from the master seed, so a change to one generator does
 * not reshuffle the others. No wall-clock time is used; all dates are relative to {@link SeedSpec#referenceDate()}.
 */
@Component
public class SyntheticDataGenerator {

	public static final int FIRST_GENERATED_EMPLOYEE_NUMBER = 2000;

	public SeedDataset generate(SeedSpec spec) {
		SeedDataset ds = new SeedDataset();
		Context ctx = new Context(spec, ds);
		addDepartments(ctx);
		addLeaveTypes(ctx);
		AnchorScenario.addEmployees(ds);
		addOrganisation(ctx);
		AnchorScenario.addActivity(ds);
		addLeave(ctx);
		addAttendance(ctx);
		addMeetings(ctx);
		addRewardsAndDisciplinary(ctx);
		addAnnouncements(ctx);
		return ds;
	}

	/** Shared state for one generation run. */
	private static final class Context {
		final SeedSpec spec;
		final SeedDataset ds;
		final LocalDate ref;
		final Instant refInstant;
		/** Active, non-anchor employees: the only ones random activity may involve. */
		final List<EmployeeRow> activeGenerated = new ArrayList<>();
		final Map<Long, List<Long>> activeByDepartment = new HashMap<>();
		final Map<Long, List<LocalDate[]>> approvedLeave = new HashMap<>();
		Long hrHeadId;

		Context(SeedSpec spec, SeedDataset ds) {
			this.spec = spec;
			this.ds = ds;
			this.ref = spec.referenceDate();
			this.refInstant = ref.atStartOfDay(WorkCalendar.ZONE).toInstant();
		}

		SplittableRandom stream(String name) {
			return new SplittableRandom(spec.seed() * 0x9E3779B97F4A7C15L + name.hashCode());
		}

		/** Approver for an employee's leave / issuer of rewards: the manager, or the HR head for top-level staff. */
		Long approverFor(EmployeeRow e) {
			if (e.managerId() != null) {
				return e.managerId();
			}
			return (hrHeadId != null && hrHeadId != e.id()) ? hrHeadId : null;
		}
	}

	// ---- Organisation ---------------------------------------------------------------------------------------------

	private void addDepartments(Context ctx) {
		for (int i = 0; i < ctx.spec.counts().departments(); i++) {
			Dept d = ReferenceData.DEPARTMENTS.get(i);
			ctx.ds.departments.add(new DepartmentRow(i + 1, d.code(), d.nameEn(), d.nameAr()));
		}
	}

	private void addLeaveTypes(Context ctx) {
		ctx.ds.leaveTypes.add(new LeaveTypeRow(1, "ANNUAL", "Annual leave", "إجازة سنوية", bd("21.0"), true));
		ctx.ds.leaveTypes.add(new LeaveTypeRow(2, "SICK", "Sick leave", "إجازة مرضية", bd("15.0"), true));
		ctx.ds.leaveTypes.add(new LeaveTypeRow(3, "CASUAL", "Casual leave", "إجازة عارضة", bd("6.0"), true));
		ctx.ds.leaveTypes.add(new LeaveTypeRow(4, "UNPAID", "Unpaid leave", "إجازة بدون أجر", bd("0.0"), false));
	}

	private void addOrganisation(Context ctx) {
		SplittableRandom rnd = ctx.stream("organisation");
		SeedDataset ds = ctx.ds;
		int generated = ctx.spec.counts().employees() - AnchorScenario.ANCHOR_EMPLOYEE_COUNT;
		int[] perDept = allocate(generated, ds.departments.size());
		int number = FIRST_GENERATED_EMPLOYEE_NUMBER;

		for (int di = 0; di < ds.departments.size(); di++) {
			DepartmentRow dept = ds.departments.get(di);
			Dept def = ReferenceData.DEPARTMENTS.get(di);
			int n = perDept[di];
			int leads = (n - 1) / 9;

			EmployeeRow head = addEmployee(ctx, rnd, number++, dept, null, "Head of " + def.nameEn(), Level.HEAD);
			if (dept.code().equals("D-HR")) {
				ctx.hrHeadId = head.id();
			}
			List<EmployeeRow> leadRows = new ArrayList<>();
			for (int l = 0; l < leads; l++) {
				leadRows.add(addEmployee(ctx, rnd, number++, dept, head.id(), def.nameEn() + " Team Lead", Level.LEAD));
			}
			for (int s = 0; s < n - 1 - leads; s++) {
				long manager = leadRows.isEmpty() ? head.id() : leadRows.get(s % leadRows.size()).id();
				boolean intern = rnd.nextDouble() < 0.03;
				String title = pick(rnd, def.staffTitles()) + (intern ? " Intern" : "");
				addEmployee(ctx, rnd, number++, dept, manager, title, intern ? Level.INTERN : Level.STAFF);
			}
		}
	}

	private EmployeeRow addEmployee(Context ctx, SplittableRandom rnd, int number, DepartmentRow dept, Long managerId,
			String title, Level level) {
		boolean female = rnd.nextBoolean();
		Name first = pick(rnd, female ? ReferenceData.FEMALE_FIRST : ReferenceData.MALE_FIRST);
		Name family = pick(rnd, ReferenceData.FAMILY);
		String code = "E-" + number;
		LocalDate hire = switch (level) {
			case HEAD -> ctx.ref.minusDays(rnd.nextInt(5 * 365, 15 * 365));
			case LEAD -> ctx.ref.minusDays(rnd.nextInt(3 * 365, 10 * 365));
			case STAFF -> ctx.ref.minusDays(rnd.nextInt(30, 8 * 365));
			case INTERN -> ctx.ref.minusDays(rnd.nextInt(30, 180));
		};
		String employmentType;
		if (level == Level.INTERN) {
			employmentType = "INTERN";
		} else if (level == Level.STAFF) {
			double r = rnd.nextDouble();
			employmentType = r < 0.07 ? "CONTRACTOR" : r < 0.12 ? "PART_TIME" : "FULL_TIME";
		} else {
			employmentType = "FULL_TIME";
		}
		String status = (level == Level.STAFF && rnd.nextDouble() < 0.02) ? "TERMINATED" : "ACTIVE";

		EmployeeRow e = new EmployeeRow(ctx.ds.employees.size() + 1, code, first.en() + " " + family.en(),
				first.ar() + " " + family.ar(), code.toLowerCase() + "@example.test", dept.id(), managerId, title,
				employmentType, hire, status, level, false);
		ctx.ds.employees.add(e);
		if (e.active()) {
			ctx.activeGenerated.add(e);
			ctx.activeByDepartment.computeIfAbsent(dept.id(), k -> new ArrayList<>()).add(e.id());
		}

		int[] range = switch (level) {
			case HEAD -> new int[] { 60_000, 90_000 };
			case LEAD -> new int[] { 35_000, 55_000 };
			case STAFF -> new int[] { 12_000, 35_000 };
			case INTERN -> new int[] { 5_000, 8_000 };
		};
		double salary = rnd.nextInt(range[0], range[1] + 1) * ("PART_TIME".equals(employmentType) ? 0.6 : 1.0);
		BigDecimal rounded = BigDecimal.valueOf(Math.round(salary / 500.0) * 500L).setScale(2, RoundingMode.UNNECESSARY);
		LocalDate yearStart = LocalDate.of(ctx.spec.leaveYear(), 1, 1);
		ctx.ds.compensations.add(new CompensationRow(e.id(), rounded, "EGP", hire.isAfter(yearStart) ? hire : yearStart));
		return e;
	}

	/** Splits {@code total} employees over departments: at least 2 each, the rest by department weight (largest remainder). */
	private static int[] allocate(int total, int departments) {
		int[] out = new int[departments];
		int weightSum = 0;
		for (int i = 0; i < departments; i++) {
			out[i] = 2;
			weightSum += ReferenceData.DEPARTMENTS.get(i).weight();
		}
		int remaining = total - 2 * departments;
		double[] remainders = new double[departments];
		int assigned = 0;
		for (int i = 0; i < departments; i++) {
			double exact = (double) remaining * ReferenceData.DEPARTMENTS.get(i).weight() / weightSum;
			out[i] += (int) Math.floor(exact);
			assigned += (int) Math.floor(exact);
			remainders[i] = exact - Math.floor(exact);
		}
		for (int k = 0; k < remaining - assigned; k++) {
			int best = 0;
			for (int i = 1; i < departments; i++) {
				if (remainders[i] > remainders[best]) {
					best = i;
				}
			}
			out[best]++;
			remainders[best] = -1;
		}
		return out;
	}

	// ---- Leave ----------------------------------------------------------------------------------------------------

	private void addLeave(Context ctx) {
		SplittableRandom rnd = ctx.stream("leave");
		SeedDataset ds = ctx.ds;
		int year = ctx.spec.leaveYear();
		LeaveTypeRow annual = ds.leaveType("ANNUAL");
		LeaveTypeRow sick = ds.leaveType("SICK");
		LeaveTypeRow casual = ds.leaveType("CASUAL");
		LeaveTypeRow unpaid = ds.leaveType("UNPAID");

		List<EmployeeRow> eligible = ctx.activeGenerated.stream()
				.filter(e -> !Long.valueOf(e.id()).equals(ctx.hrHeadId)).toList();
		// used/pending per employee per balance type, and booked ranges per employee
		Map<Long, Map<Long, BigDecimal[]>> usage = new HashMap<>();
		Map<Long, BigDecimal> annualEntitlement = new HashMap<>();
		Map<Long, List<LocalDate[]>> booked = new HashMap<>();
		for (EmployeeRow e : ctx.activeGenerated) {
			boolean longService = ChronoUnit.YEARS.between(e.hireDate(), LocalDate.of(year, 1, 1)) >= 10;
			annualEntitlement.put(e.id(), longService ? bd("30.0") : bd("21.0"));
		}

		int target = Math.max(0, ctx.spec.counts().leaveRequests() - ds.leaveRequests.size());
		int created = 0;
		int code = 2000;
		LocalDate yearStart = LocalDate.of(year, 1, 1);
		for (int attempt = 0; attempt < target * 6 && created < target && !eligible.isEmpty(); attempt++) {
			EmployeeRow e = eligible.get(rnd.nextInt(eligible.size()));
			double t = rnd.nextDouble();
			LeaveTypeRow type = t < 0.60 ? annual : t < 0.85 ? sick : t < 0.95 ? casual : unpaid;
			int days = type == sick ? rnd.nextInt(1, 4) : type == casual ? 1 : type == unpaid ? rnd.nextInt(1, 6)
					: (rnd.nextDouble() < 0.7 ? rnd.nextInt(1, 4) : rnd.nextInt(4, 8));
			LocalDate start = WorkCalendar.onOrAfter(yearStart.plusDays(rnd.nextInt(0, 355)));
			if (start.isBefore(e.hireDate()) || start.getYear() != year) {
				continue;
			}
			LocalDate end = WorkCalendar.endAfterWorkdays(start, days);
			if (end.getYear() != year || overlaps(booked.get(e.id()), start, end)) {
				continue;
			}

			String status = status(rnd, ctx.ref, start, end, type == sick);
			boolean counts = status.equals("APPROVED") || status.equals("PENDING");
			BigDecimal amount = BigDecimal.valueOf(days).setScale(1);
			if (counts && type.hasBalance()) {
				BigDecimal entitled = type == annual ? annualEntitlement.get(e.id()) : type.defaultAnnualDays();
				BigDecimal[] u = usage.computeIfAbsent(e.id(), k -> new HashMap<>())
						.computeIfAbsent(type.id(), k -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
				if (u[0].add(u[1]).add(amount).compareTo(entitled) > 0) {
					continue; // would exceed the entitlement
				}
				if (status.equals("APPROVED")) {
					u[0] = u[0].add(amount);
				} else {
					u[1] = u[1].add(amount);
				}
			}

			// Submission: sick leave is reported on/after the first day, other leave in advance; never after "today".
			LocalDate submitted = type == sick ? start.plusDays(rnd.nextInt(0, 3)) : start.minusDays(rnd.nextInt(3, 31));
			if (!submitted.isBefore(ctx.ref)) {
				submitted = ctx.ref.minusDays(rnd.nextInt(1, 11));
			}
			Instant decidedAt = null;
			Long approver = null;
			if (status.equals("APPROVED") || status.equals("REJECTED")) {
				LocalDate decided = submitted.plusDays(rnd.nextInt(0, 4));
				if (!decided.isBefore(ctx.ref)) {
					decided = submitted;
				}
				decidedAt = AnchorScenario.cairo(decided.atTime(14, 0));
				approver = ctx.approverFor(e);
			}
			ds.leaveRequests.add(new LeaveRequestRow(ds.leaveRequests.size() + 1, "LR-" + code++, e.id(), type.id(),
					start, end, amount, status, approver, pick(rnd, ReferenceData.LEAVE_REASONS),
					AnchorScenario.cairo(submitted.atTime(10, 0)), decidedAt));
			booked.computeIfAbsent(e.id(), k -> new ArrayList<>()).add(new LocalDate[] { start, end });
			if (status.equals("APPROVED")) {
				ctx.approvedLeave.computeIfAbsent(e.id(), k -> new ArrayList<>()).add(new LocalDate[] { start, end });
			}
			created++;
		}

		// Balances for every active generated employee and every balance type, consistent with the requests above.
		for (EmployeeRow e : ctx.activeGenerated) {
			for (LeaveTypeRow type : List.of(annual, sick, casual)) {
				BigDecimal entitled = type == annual ? annualEntitlement.get(e.id()) : type.defaultAnnualDays();
				BigDecimal[] u = usage.getOrDefault(e.id(), Map.of())
						.getOrDefault(type.id(), new BigDecimal[] { bd("0.0"), bd("0.0") });
				ds.leaveBalances.add(new LeaveBalanceRow(ds.leaveBalances.size() + 1, e.id(), type.id(), (short) year,
						entitled, u[0].setScale(1), u[1].setScale(1)));
			}
		}
	}

	private static String status(SplittableRandom rnd, LocalDate ref, LocalDate start, LocalDate end, boolean sick) {
		double r = rnd.nextDouble();
		if (end.isBefore(ref)) {
			if (sick) {
				return r < 0.95 ? "APPROVED" : "REJECTED";
			}
			return r < 0.85 ? "APPROVED" : r < 0.95 ? "REJECTED" : "CANCELLED";
		}
		if (start.isAfter(ref)) {
			return r < 0.50 ? "PENDING" : r < 0.90 ? "APPROVED" : r < 0.95 ? "REJECTED" : "CANCELLED";
		}
		return "APPROVED"; // in progress on the reference date
	}

	private static boolean overlaps(List<LocalDate[]> ranges, LocalDate start, LocalDate end) {
		if (ranges == null) {
			return false;
		}
		for (LocalDate[] r : ranges) {
			if (!start.isAfter(r[1]) && !end.isBefore(r[0])) {
				return true;
			}
		}
		return false;
	}

	// ---- Attendance -----------------------------------------------------------------------------------------------

	private void addAttendance(Context ctx) {
		SplittableRandom profiles = ctx.stream("attendance-profile");
		SplittableRandom rnd = ctx.stream("attendance");
		List<LocalDate> window = WorkCalendar.workdaysBefore(ctx.ref, ctx.spec.counts().attendanceWorkdays());
		Set<String> remoteFriendly = Set.of("D-ENG", "D-IT", "D-DAT", "D-MKT", "D-PRD", "D-UX", "D-MOB", "D-INF", "D-SEC");

		for (EmployeeRow e : ctx.activeGenerated) {
			double latePropensity = new double[] { 0.02, 0.05, 0.10, 0.25 }[profiles.nextInt(4)];
			boolean remoteAllowed = remoteFriendly.contains(ctx.ds.departments.get((int) e.departmentId() - 1).code());
			double remoteShare = remoteAllowed ? 0.2 : 0.0;
			List<LocalDate[]> leave = ctx.approvedLeave.getOrDefault(e.id(), List.of());

			for (LocalDate day : window) {
				if (day.isBefore(e.hireDate())) {
					continue;
				}
				if (overlaps(leave, day, day)) {
					ctx.ds.attendance.add(new AttendanceRow(ctx.ds.attendance.size() + 1, e.id(), day, null, null, 0,
							"ON_LEAVE"));
					continue;
				}
				double r = rnd.nextDouble();
				if (r < 0.01) {
					ctx.ds.attendance.add(new AttendanceRow(ctx.ds.attendance.size() + 1, e.id(), day, null, null, 0,
							"ABSENT"));
					continue;
				}
				boolean lateEvent = rnd.nextDouble() < latePropensity;
				int offset = lateEvent ? rnd.nextInt(WorkCalendar.LATE_GRACE_MINUTES + 1, 121) : rnd.nextInt(-20, 15);
				int minutesLate = Math.max(0, offset);
				String status = lateEvent ? "LATE" : (rnd.nextDouble() < remoteShare ? "REMOTE" : "PRESENT");
				Instant in = AnchorScenario.cairo(day.atTime(WorkCalendar.WORKDAY_START).plusMinutes(offset));
				Instant out = in.plus(8 * 60 + rnd.nextInt(0, 61), ChronoUnit.MINUTES);
				ctx.ds.attendance.add(new AttendanceRow(ctx.ds.attendance.size() + 1, e.id(), day, in, out, minutesLate,
						status));
			}
		}
	}

	// ---- Meetings -------------------------------------------------------------------------------------------------

	private void addMeetings(Context ctx) {
		SplittableRandom rnd = ctx.stream("meetings");
		SeedDataset ds = ctx.ds;
		int target = Math.max(0, ctx.spec.counts().meetings() - ds.meetings.size());
		List<EmployeeRow> managers = ctx.activeGenerated.stream().filter(e -> e.level() == Level.HEAD || e.level() == Level.LEAD)
				.toList();
		int number = 2000;
		for (int i = 0; i < target; i++) {
			EmployeeRow organizer = (rnd.nextDouble() < 0.7 && !managers.isEmpty()) ? pick(rnd, managers)
					: pick(rnd, ctx.activeGenerated);
			LocalDate day = WorkCalendar.onOrAfter(ctx.ref.minusDays(60).plusDays(rnd.nextInt(0, 91)));
			LocalDateTime start = day.atTime(LocalTime.of(rnd.nextInt(9, 17), rnd.nextBoolean() ? 0 : 30));
			int minutes = new int[] { 30, 45, 60, 90 }[rnd.nextInt(4)];
			Instant startsAt = AnchorScenario.cairo(start);
			boolean past = startsAt.isBefore(ctx.refInstant);
			double r = rnd.nextDouble();
			String status = past ? (r < 0.92 ? "COMPLETED" : "CANCELLED")
					: (r < 0.80 ? "SCHEDULED" : r < 0.90 ? "PENDING_APPROVAL" : "CANCELLED");
			boolean approval = status.equals("PENDING_APPROVAL") || rnd.nextDouble() < 0.1;
			long id = ds.meetings.size() + 1;
			ds.meetings.add(new MeetingRow(id, "M-" + number++, pick(rnd, ReferenceData.MEETING_TITLES), organizer.id(),
					startsAt, startsAt.plus(minutes, ChronoUnit.MINUTES), pick(rnd, ReferenceData.LOCATIONS), approval,
					status));

			boolean completed = status.equals("COMPLETED");
			ds.invitations.add(new InvitationRow(id, organizer.id(), "ACCEPTED", completed ? Boolean.TRUE : null));
			Set<Long> invited = new LinkedHashSet<>();
			invited.add(organizer.id());
			List<Long> sameDept = ctx.activeByDepartment.getOrDefault(organizer.departmentId(), List.of());
			int guests = rnd.nextInt(2, 9);
			for (int g = 0, tries = 0; g < guests && tries < guests * 4; tries++) {
				long guest = (rnd.nextDouble() < 0.8 && sameDept.size() > 1) ? pick(rnd, sameDept)
						: pick(rnd, ctx.activeGenerated).id();
				if (!invited.add(guest)) {
					continue;
				}
				g++;
				double q = rnd.nextDouble();
				String response;
				Boolean attended = null;
				if (completed) {
					response = q < 0.85 ? "ACCEPTED" : q < 0.95 ? "DECLINED" : "TENTATIVE";
					attended = response.equals("DECLINED") ? Boolean.FALSE : rnd.nextDouble() < 0.9;
				} else {
					response = q < 0.40 ? "PENDING" : q < 0.80 ? "ACCEPTED" : q < 0.90 ? "DECLINED" : "TENTATIVE";
				}
				ds.invitations.add(new InvitationRow(id, guest, response, attended));
			}
		}
	}

	// ---- Rewards and disciplinary ---------------------------------------------------------------------------------

	private void addRewardsAndDisciplinary(Context ctx) {
		SplittableRandom rnd = ctx.stream("recognition");
		SeedDataset ds = ctx.ds;
		int rewardTarget = Math.max(0, ctx.spec.counts().rewards() - ds.rewards.size());
		for (int i = 0, tries = 0; i < rewardTarget && tries < rewardTarget * 10; tries++) {
			EmployeeRow e = pick(rnd, ctx.activeGenerated);
			LocalDate on = ctx.ref.minusDays(rnd.nextInt(1, 731));
			if (on.isBefore(e.hireDate())) {
				continue;
			}
			double t = rnd.nextDouble();
			String type = t < 0.40 ? "SPOT_AWARD" : t < 0.60 ? "CERTIFICATE" : t < 0.75 ? "EMPLOYEE_OF_MONTH"
					: t < 0.90 ? "BONUS" : "PROMOTION_RECOGNITION";
			String description = type.equals("EMPLOYEE_OF_MONTH")
					? "Employee of the month for " + on.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " "
							+ on.getYear()
					: pick(rnd, ReferenceData.REWARD_REASONS);
			ds.rewards.add(new RewardRow(ds.rewards.size() + 1, e.id(), type, on, ctx.approverFor(e), description));
			i++;
		}

		List<EmployeeRow> staff = ctx.activeGenerated.stream().filter(e -> e.level() == Level.STAFF).toList();
		int disciplinaryTarget = Math.max(0, ctx.spec.counts().disciplinaryRecords() - ds.disciplinary.size());
		for (int i = 0, tries = 0; i < disciplinaryTarget && tries < disciplinaryTarget * 10 && !staff.isEmpty(); tries++) {
			EmployeeRow e = pick(rnd, staff);
			LocalDate on = ctx.ref.minusDays(rnd.nextInt(1, 541));
			if (on.isBefore(e.hireDate())) {
				continue;
			}
			double t = rnd.nextDouble();
			String type = t < 0.45 ? "VERBAL_WARNING" : t < 0.75 ? "WRITTEN_WARNING" : t < 0.88 ? "DEDUCTION"
					: t < 0.96 ? "FINAL_WARNING" : "SUSPENSION";
			ds.disciplinary.add(new DisciplinaryRow(ds.disciplinary.size() + 1, e.id(), type, on, ctx.approverFor(e),
					pick(rnd, ReferenceData.DISCIPLINARY_REASONS)));
			i++;
		}
	}

	// ---- Announcements --------------------------------------------------------------------------------------------

	private void addAnnouncements(Context ctx) {
		SplittableRandom rnd = ctx.stream("announcements");
		SeedDataset ds = ctx.ds;
		int target = Math.max(0, ctx.spec.counts().announcements() - ds.announcements.size());
		int number = 2000;
		for (int i = 0; i < target; i++) {
			Bilingual t = pick(rnd, ReferenceData.ANNOUNCEMENTS);
			boolean arabic = rnd.nextBoolean();
			boolean departmental = t.titleEn().contains("{dept}");
			DepartmentRow dept = departmental ? pick(rnd, ds.departments) : null;
			String deptName = dept == null ? "" : (arabic ? dept.nameAr() : dept.nameEn());
			String title = (arabic ? t.titleAr() : t.titleEn()).replace("{dept}", deptName);
			String body = (arabic ? t.bodyAr() : t.bodyEn()).replace("{dept}", deptName);
			LocalDateTime published = ctx.ref.minusDays(rnd.nextInt(1, 366)).atTime(rnd.nextInt(9, 13), 0);
			Instant publishedAt = AnchorScenario.cairo(published);
			Instant expiresAt = rnd.nextDouble() < 0.3
					? AnchorScenario.cairo(published.plusDays(rnd.nextInt(7, 61)))
					: null;
			ds.announcements.add(new AnnouncementRow(ds.announcements.size() + 1, "A-" + number++, title, body,
					arabic ? "ar" : "en", departmental ? "DEPARTMENT" : "COMPANY", dept == null ? null : dept.id(),
					publishedAt, expiresAt));
		}
	}

	// ---- utilities ------------------------------------------------------------------------------------------------

	private static <T> T pick(SplittableRandom rnd, List<T> list) {
		return list.get(rnd.nextInt(list.size()));
	}

	private static BigDecimal bd(String v) {
		return new BigDecimal(v);
	}
}
