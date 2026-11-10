package com.mimocodes.hrisrag.data.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mimocodes.hrisrag.data.attendance.entity.AttendanceRecord;
import com.mimocodes.hrisrag.data.attendance.entity.AttendanceStatus;
import com.mimocodes.hrisrag.data.attendance.repository.AttendanceRecordRepository;
import com.mimocodes.hrisrag.data.employee.entity.Employee;
import com.mimocodes.hrisrag.data.employee.repository.EmployeeRepository;
import com.mimocodes.hrisrag.data.leave.entity.LeaveBalance;
import com.mimocodes.hrisrag.data.leave.repository.LeaveBalanceRepository;
import com.mimocodes.hrisrag.data.leave.repository.LeaveRequestRepository;
import com.mimocodes.hrisrag.data.meeting.entity.Meeting;
import com.mimocodes.hrisrag.data.meeting.repository.MeetingInvitationRepository;

/**
 * Read-only HRIS queries for other modules (architecture rule 1: nobody outside {@code data} touches repositories).
 * Returns plain records, never entities, so lazy relations are resolved inside the read-only transaction. It performs
 * NO authorization: callers (the tools module) authorize against the caller's identity first.
 * <p>
 * Deliberately absent: compensation and disciplinary records. Nothing here can expose them.
 */
@Service
@Transactional(readOnly = true)
public class HrisQueryService {

	public record EmployeeRef(String employeeCode, String departmentCode, String managerCode, String status) {
	}

	public record Balance(String employeeCode, String leaveType, int year, BigDecimal entitledDays, BigDecimal usedDays,
			BigDecimal pendingDays, BigDecimal remainingDays) {
	}

	public record Request(String requestCode, String employeeCode, String leaveType, LocalDate startDate,
			LocalDate endDate, BigDecimal days, String status, Instant submittedAt, Instant decidedAt) {
	}

	public record MeetingView(String meetingCode, String title, Instant startsAt, Instant endsAt, String location,
			String status) {
	}

	public record AttendanceSummary(String employeeCode, String month, int recordedDays, int presentDays, int lateDays,
			int absentDays, int remoteDays, int onLeaveDays, int totalMinutesLate, List<LocalDate> lateDates) {
	}

	private final EmployeeRepository employees;
	private final LeaveBalanceRepository balances;
	private final LeaveRequestRepository requests;
	private final MeetingInvitationRepository invitations;
	private final AttendanceRecordRepository attendance;

	public HrisQueryService(EmployeeRepository employees, LeaveBalanceRepository balances,
			LeaveRequestRepository requests, MeetingInvitationRepository invitations,
			AttendanceRecordRepository attendance) {
		this.employees = employees;
		this.balances = balances;
		this.requests = requests;
		this.invitations = invitations;
		this.attendance = attendance;
	}

	public Optional<EmployeeRef> employee(String employeeCode) {
		return employees.findByEmployeeCode(employeeCode).map(HrisQueryService::ref);
	}

	public Optional<Balance> balance(String employeeCode, String leaveType, int year) {
		return balances.findByEmployee_EmployeeCodeAndLeaveType_CodeAndYear(employeeCode, leaveType, (short) year)
				.map(HrisQueryService::balance);
	}

	/** The request plus its owner (needed for authorization). */
	public Optional<Request> request(String requestCode) {
		return requests.findByRequestCode(requestCode).map(r -> new Request(r.getRequestCode(),
				r.getEmployee().getEmployeeCode(), r.getLeaveType().getCode(), r.getStartDate(), r.getEndDate(),
				r.getDays(), r.getStatus().name(), r.getSubmittedAt(), r.getDecidedAt()));
	}

	/** Meetings after {@code now}, soonest first, excluding cancelled meetings and declined invitations. */
	public List<MeetingView> upcomingMeetings(String employeeCode, Instant now, int limit) {
		return invitations.findUpcomingMeetings(employeeCode, now).stream().limit(limit)
				.map(HrisQueryService::meeting).toList();
	}

	public AttendanceSummary attendance(String employeeCode, YearMonth month) {
		List<AttendanceRecord> rows = attendance.findByEmployee_EmployeeCodeAndWorkDateBetweenOrderByWorkDate(
				employeeCode, month.atDay(1), month.atEndOfMonth());
		return new AttendanceSummary(employeeCode, month.toString(), rows.size(), count(rows, AttendanceStatus.PRESENT),
				count(rows, AttendanceStatus.LATE), count(rows, AttendanceStatus.ABSENT),
				count(rows, AttendanceStatus.REMOTE), count(rows, AttendanceStatus.ON_LEAVE),
				rows.stream().filter(r -> r.getStatus() == AttendanceStatus.LATE).mapToInt(AttendanceRecord::getMinutesLate)
						.sum(),
				rows.stream().filter(r -> r.getStatus() == AttendanceStatus.LATE).map(AttendanceRecord::getWorkDate)
						.toList());
	}

	private static int count(List<AttendanceRecord> rows, AttendanceStatus s) {
		return (int) rows.stream().filter(r -> r.getStatus() == s).count();
	}

	private static EmployeeRef ref(Employee e) {
		return new EmployeeRef(e.getEmployeeCode(), e.getDepartment().getCode(),
				e.getManager() == null ? null : e.getManager().getEmployeeCode(), e.getStatus().name());
	}

	private static Balance balance(LeaveBalance b) {
		return new Balance(b.getEmployee().getEmployeeCode(), b.getLeaveType().getCode(), b.getYear(),
				b.getEntitledDays(), b.getUsedDays(), b.getPendingDays(), b.remainingDays());
	}

	private static MeetingView meeting(Meeting m) {
		return new MeetingView(m.getMeetingCode(), m.getTitle(), m.getStartsAt(), m.getEndsAt(), m.getLocation(),
				m.getStatus().name());
	}
}
