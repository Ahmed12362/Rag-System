package com.mimocodes.hrisrag.data;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import com.mimocodes.hrisrag.data.announcement.entity.Announcement;
import com.mimocodes.hrisrag.data.announcement.entity.AnnouncementScope;
import com.mimocodes.hrisrag.data.announcement.repository.AnnouncementRepository;
import com.mimocodes.hrisrag.data.attendance.entity.AttendanceRecord;
import com.mimocodes.hrisrag.data.attendance.entity.AttendanceStatus;
import com.mimocodes.hrisrag.data.attendance.repository.AttendanceRecordRepository;
import com.mimocodes.hrisrag.data.employee.entity.Department;
import com.mimocodes.hrisrag.data.employee.entity.Employee;
import com.mimocodes.hrisrag.data.leave.entity.LeaveBalance;
import com.mimocodes.hrisrag.data.leave.entity.LeaveRequest;
import com.mimocodes.hrisrag.data.leave.entity.LeaveRequestStatus;
import com.mimocodes.hrisrag.data.leave.entity.LeaveType;
import com.mimocodes.hrisrag.data.leave.repository.LeaveBalanceRepository;
import com.mimocodes.hrisrag.data.leave.repository.LeaveRequestRepository;
import com.mimocodes.hrisrag.data.meeting.entity.Meeting;
import com.mimocodes.hrisrag.data.meeting.entity.MeetingStatus;
import com.mimocodes.hrisrag.data.meeting.repository.MeetingRepository;

/**
 * The database is the last line of defence for data integrity: each invalid write must be rejected by a V2
 * constraint and surface as Spring's DataIntegrityViolationException.
 */
class HrisConstraintTest extends HrisJpaTestBase {

	@Autowired LeaveRequestRepository leaveRequests;
	@Autowired LeaveBalanceRepository balances;
	@Autowired AttendanceRecordRepository attendance;
	@Autowired AnnouncementRepository announcements;
	@Autowired MeetingRepository meetings;

	private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

	@Test
	void leaveRequestEndBeforeStartRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		LeaveType annual = leaveType("ANNUAL", 21);
		assertRejected(() -> leaveRequests.saveAndFlush(new LeaveRequest("LR-1", e, annual, LocalDate.of(2026, 12, 5),
				LocalDate.of(2026, 12, 1), BigDecimal.ONE, LeaveRequestStatus.PENDING, null, null, NOW, null)));
	}

	@Test
	void approvedLeaveRequestWithoutDecisionTimeRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		LeaveType annual = leaveType("ANNUAL", 21);
		assertRejected(() -> leaveRequests.saveAndFlush(new LeaveRequest("LR-1", e, annual, LocalDate.of(2026, 12, 1),
				LocalDate.of(2026, 12, 1), BigDecimal.ONE, LeaveRequestStatus.APPROVED, null, null, NOW, null)));
	}

	@Test
	void pendingLeaveRequestWithDecisionTimeRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		LeaveType annual = leaveType("ANNUAL", 21);
		assertRejected(() -> leaveRequests.saveAndFlush(new LeaveRequest("LR-1", e, annual, LocalDate.of(2026, 12, 1),
				LocalDate.of(2026, 12, 1), BigDecimal.ONE, LeaveRequestStatus.PENDING, null, null, NOW, NOW)));
	}

	@Test
	void duplicateRequestCodeRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		LeaveType annual = leaveType("ANNUAL", 21);
		leaveRequests.saveAndFlush(new LeaveRequest("LR-1", e, annual, LocalDate.of(2026, 12, 1),
				LocalDate.of(2026, 12, 1), BigDecimal.ONE, LeaveRequestStatus.PENDING, null, null, NOW, null));
		assertRejected(() -> leaveRequests.saveAndFlush(new LeaveRequest("LR-1", e, annual, LocalDate.of(2026, 12, 3),
				LocalDate.of(2026, 12, 3), BigDecimal.ONE, LeaveRequestStatus.PENDING, null, null, NOW, null)));
	}

	@Test
	void secondBalanceForSameEmployeeTypeYearRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		LeaveType annual = leaveType("ANNUAL", 21);
		balances.saveAndFlush(new LeaveBalance(e, annual, (short) 2026, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO));
		assertRejected(() -> balances.saveAndFlush(
				new LeaveBalance(e, annual, (short) 2026, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO)));
	}

	@Test
	void duplicateAttendanceDayRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		attendance.saveAndFlush(new AttendanceRecord(e, LocalDate.of(2026, 11, 9), null, null, 0, AttendanceStatus.ABSENT));
		assertRejected(() -> attendance.saveAndFlush(
				new AttendanceRecord(e, LocalDate.of(2026, 11, 9), null, null, 0, AttendanceStatus.PRESENT)));
	}

	@Test
	void checkoutBeforeCheckinRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		assertRejected(() -> attendance.saveAndFlush(new AttendanceRecord(e, LocalDate.of(2026, 11, 9),
				Instant.parse("2026-11-09T15:00:00Z"), Instant.parse("2026-11-09T07:00:00Z"), 0, AttendanceStatus.PRESENT)));
	}

	@Test
	void departmentAnnouncementWithoutDepartmentRejected() {
		assertRejected(() -> announcements.saveAndFlush(
				new Announcement("A-1", "t", "b", "en", AnnouncementScope.DEPARTMENT, null, NOW, null)));
	}

	@Test
	void companyAnnouncementWithDepartmentRejected() {
		Department eng = department("D-ENG");
		assertRejected(() -> announcements.saveAndFlush(
				new Announcement("A-1", "t", "b", "en", AnnouncementScope.COMPANY, eng, NOW, null)));
	}

	@Test
	void unsupportedAnnouncementLanguageRejected() {
		assertRejected(() -> announcements.saveAndFlush(
				new Announcement("A-1", "t", "b", "fr", AnnouncementScope.COMPANY, null, NOW, null)));
	}

	@Test
	void meetingEndingBeforeStartRejected() {
		Employee org = employee("E-1000", department("D-ENG"), null);
		assertRejected(() -> meetings.saveAndFlush(new Meeting("M-1", "t", org, NOW, NOW.minusSeconds(60), null, false,
				MeetingStatus.SCHEDULED)));
	}

	@Test
	void employeeAsOwnManagerRejected() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		e.assignManager(e);
		assertRejected(() -> employees.saveAndFlush(e));
	}

	@Test
	void duplicateEmployeeCodeRejected() {
		Department eng = department("D-ENG");
		employee("E-1001", eng, null);
		employees.flush();
		assertRejected(() -> {
			employee("E-1001", eng, null);
			employees.flush();
		});
	}

	private static void assertRejected(Runnable write) {
		assertThatThrownBy(write::run).isInstanceOf(DataIntegrityViolationException.class);
	}
}
