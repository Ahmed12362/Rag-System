package com.mimocodes.hrisrag.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mimocodes.hrisrag.data.announcement.entity.Announcement;
import com.mimocodes.hrisrag.data.announcement.entity.AnnouncementScope;
import com.mimocodes.hrisrag.data.announcement.repository.AnnouncementRepository;
import com.mimocodes.hrisrag.data.attendance.entity.AttendanceRecord;
import com.mimocodes.hrisrag.data.attendance.entity.AttendanceStatus;
import com.mimocodes.hrisrag.data.attendance.repository.AttendanceRecordRepository;
import com.mimocodes.hrisrag.data.employee.entity.Department;
import com.mimocodes.hrisrag.data.employee.entity.DisciplinaryActionType;
import com.mimocodes.hrisrag.data.employee.entity.DisciplinaryRecord;
import com.mimocodes.hrisrag.data.employee.entity.Employee;
import com.mimocodes.hrisrag.data.employee.entity.EmployeeCompensation;
import com.mimocodes.hrisrag.data.employee.entity.EmployeeReward;
import com.mimocodes.hrisrag.data.employee.entity.RewardType;
import com.mimocodes.hrisrag.data.employee.repository.DisciplinaryRecordRepository;
import com.mimocodes.hrisrag.data.employee.repository.EmployeeCompensationRepository;
import com.mimocodes.hrisrag.data.employee.repository.EmployeeRewardRepository;
import com.mimocodes.hrisrag.data.leave.entity.LeaveBalance;
import com.mimocodes.hrisrag.data.leave.entity.LeaveRequest;
import com.mimocodes.hrisrag.data.leave.entity.LeaveRequestStatus;
import com.mimocodes.hrisrag.data.leave.entity.LeaveType;
import com.mimocodes.hrisrag.data.leave.repository.LeaveBalanceRepository;
import com.mimocodes.hrisrag.data.leave.repository.LeaveRequestRepository;
import com.mimocodes.hrisrag.data.meeting.entity.InvitationResponse;
import com.mimocodes.hrisrag.data.meeting.entity.Meeting;
import com.mimocodes.hrisrag.data.meeting.entity.MeetingInvitation;
import com.mimocodes.hrisrag.data.meeting.entity.MeetingStatus;
import com.mimocodes.hrisrag.data.meeting.repository.MeetingInvitationRepository;
import com.mimocodes.hrisrag.data.meeting.repository.MeetingRepository;

import jakarta.persistence.EntityManager;

/** Round-trips and the representative queries that later tools (M8) depend on. */
class HrisRepositoryTest extends HrisJpaTestBase {

	@Autowired EmployeeCompensationRepository compensations;
	@Autowired EmployeeRewardRepository rewards;
	@Autowired DisciplinaryRecordRepository disciplinary;
	@Autowired AttendanceRecordRepository attendance;
	@Autowired LeaveBalanceRepository balances;
	@Autowired LeaveRequestRepository leaveRequests;
	@Autowired MeetingRepository meetings;
	@Autowired MeetingInvitationRepository invitations;
	@Autowired AnnouncementRepository announcements;
	@Autowired EntityManager em;

	private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

	@Test
	void employeeByCodeAndDirectReports() {
		Department eng = department("D-ENG");
		Employee lead = employee("E-1000", eng, null);
		employee("E-1001", eng, lead);
		employee("E-1002", eng, lead);
		clearContext();

		Employee found = employees.findByEmployeeCode("E-1001").orElseThrow();
		assertThat(found.getDepartment().getCode()).isEqualTo("D-ENG");
		assertThat(found.getManager().getEmployeeCode()).isEqualTo("E-1000");
		assertThat(employees.findByManager_EmployeeCode("E-1000"))
				.extracting(Employee::getEmployeeCode).containsExactlyInAnyOrder("E-1001", "E-1002");
	}

	@Test
	void compensationSharesEmployeePrimaryKey() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		compensations.save(new EmployeeCompensation(e, new BigDecimal("30000.00"), "EGP", LocalDate.of(2025, 1, 1)));
		clearContext();

		EmployeeCompensation c = compensations.findById(e.getId()).orElseThrow();
		assertThat(c.getMonthlySalary()).isEqualByComparingTo("30000.00");
		assertThat(c.getCurrency()).isEqualTo("EGP");
	}

	@Test
	void rewardsAndDisciplinaryOrderedNewestFirst() {
		Department eng = department("D-ENG");
		Employee lead = employee("E-1000", eng, null);
		Employee e = employee("E-1001", eng, lead);
		rewards.save(new EmployeeReward(e, RewardType.SPOT_AWARD, LocalDate.of(2025, 3, 1), lead, "Release support"));
		rewards.save(new EmployeeReward(e, RewardType.EMPLOYEE_OF_MONTH, LocalDate.of(2026, 5, 1), lead, "May"));
		disciplinary.save(new DisciplinaryRecord(e, DisciplinaryActionType.VERBAL_WARNING, LocalDate.of(2026, 2, 1),
				lead, "Repeated lateness"));
		clearContext();

		assertThat(rewards.findByEmployee_EmployeeCodeOrderByAwardedOnDesc("E-1001"))
				.extracting(EmployeeReward::getRewardType)
				.containsExactly(RewardType.EMPLOYEE_OF_MONTH, RewardType.SPOT_AWARD);
		assertThat(disciplinary.findByEmployee_EmployeeCodeOrderByIssuedOnDesc("E-1001")).hasSize(1);
	}

	@Test
	void remainingAnnualLeaveIsDerived() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		LeaveType annual = leaveType("ANNUAL", 21);
		balances.save(new LeaveBalance(e, annual, (short) 2026, new BigDecimal("21.0"), new BigDecimal("6.0"),
				new BigDecimal("2.0")));
		clearContext();

		LeaveBalance b = balances.findByEmployee_EmployeeCodeAndLeaveType_CodeAndYear("E-1001", "ANNUAL", (short) 2026)
				.orElseThrow();
		assertThat(b.remainingDays()).isEqualByComparingTo("13.0");
		assertThat(balances.findByEmployee_EmployeeCodeAndYear("E-1001", (short) 2026)).hasSize(1);
	}

	@Test
	void leaveRequestStatusByCode() {
		Department eng = department("D-ENG");
		Employee lead = employee("E-1000", eng, null);
		Employee e = employee("E-1001", eng, lead);
		LeaveType annual = leaveType("ANNUAL", 21);
		leaveRequests.save(new LeaveRequest("LR-1005", e, annual, LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 2),
				new BigDecimal("2.0"), LeaveRequestStatus.APPROVED, lead, "Family trip", NOW.minus(Duration.ofDays(3)),
				NOW.minus(Duration.ofDays(2))));
		clearContext();

		LeaveRequest r = leaveRequests.findByRequestCode("LR-1005").orElseThrow();
		assertThat(r.getStatus()).isEqualTo(LeaveRequestStatus.APPROVED);
		assertThat(r.getApprover().getEmployeeCode()).isEqualTo("E-1000");
		assertThat(r.getDecidedAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
		assertThat(leaveRequests.findByRequestCode("LR-9999")).isEmpty();
	}

	@Test
	void attendanceForDateRange() {
		Employee e = employee("E-1001", department("D-ENG"), null);
		attendance.save(new AttendanceRecord(e, LocalDate.of(2026, 11, 8), Instant.parse("2026-11-08T07:00:00Z"),
				Instant.parse("2026-11-08T15:00:00Z"), 0, AttendanceStatus.PRESENT));
		attendance.save(new AttendanceRecord(e, LocalDate.of(2026, 11, 9), Instant.parse("2026-11-09T07:20:00Z"),
				Instant.parse("2026-11-09T15:30:00Z"), 20, AttendanceStatus.LATE));
		attendance.save(new AttendanceRecord(e, LocalDate.of(2026, 10, 1), null, null, 0, AttendanceStatus.ABSENT));
		clearContext();

		assertThat(attendance.findByEmployee_EmployeeCodeAndWorkDateBetweenOrderByWorkDate("E-1001",
				LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)))
				.extracting(AttendanceRecord::getMinutesLate).containsExactly(0, 20);
	}

	@Test
	void upcomingMeetingsExcludePastCancelledAndDeclined() {
		Department eng = department("D-ENG");
		Employee org = employee("E-1000", eng, null);
		Employee e = employee("E-1001", eng, org);
		Meeting past = meeting("M-0001", org, NOW.minus(Duration.ofDays(1)), MeetingStatus.COMPLETED);
		Meeting soon = meeting("M-0002", org, NOW.plus(Duration.ofHours(2)), MeetingStatus.SCHEDULED);
		Meeting later = meeting("M-0003", org, NOW.plus(Duration.ofDays(3)), MeetingStatus.PENDING_APPROVAL);
		Meeting cancelled = meeting("M-0004", org, NOW.plus(Duration.ofDays(1)), MeetingStatus.CANCELLED);
		Meeting declined = meeting("M-0005", org, NOW.plus(Duration.ofDays(2)), MeetingStatus.SCHEDULED);
		invitations.save(new MeetingInvitation(past, e, InvitationResponse.ACCEPTED, true));
		invitations.save(new MeetingInvitation(later, e, InvitationResponse.PENDING, null));
		invitations.save(new MeetingInvitation(soon, e, InvitationResponse.ACCEPTED, null));
		invitations.save(new MeetingInvitation(cancelled, e, InvitationResponse.ACCEPTED, null));
		invitations.save(new MeetingInvitation(declined, e, InvitationResponse.DECLINED, null));
		clearContext();

		assertThat(invitations.findUpcomingMeetings("E-1001", NOW))
				.extracting(Meeting::getMeetingCode).containsExactly("M-0002", "M-0003");
	}

	@Test
	void announcementsVisibleToDepartment() {
		Department eng = department("D-ENG");
		Department hr = department("D-HR");
		announcement("A-001", AnnouncementScope.COMPANY, null, NOW.minus(Duration.ofDays(5)), null);
		announcement("A-002", AnnouncementScope.DEPARTMENT, eng, NOW.minus(Duration.ofDays(1)), null);
		announcement("A-003", AnnouncementScope.DEPARTMENT, hr, NOW.minus(Duration.ofDays(1)), null);
		announcement("A-004", AnnouncementScope.COMPANY, null, NOW.minus(Duration.ofDays(10)),
				NOW.minus(Duration.ofDays(2)));
		announcement("A-005", AnnouncementScope.COMPANY, null, NOW.plus(Duration.ofDays(1)), null);
		clearContext();

		assertThat(announcements.findVisibleTo("D-ENG", NOW))
				.extracting(Announcement::getAnnouncementCode).containsExactly("A-002", "A-001");
	}

	private Meeting meeting(String code, Employee organizer, Instant startsAt, MeetingStatus status) {
		return meetings.save(new Meeting(code, "Meeting " + code, organizer, startsAt,
				startsAt.plus(Duration.ofHours(1)), "Room 1", status == MeetingStatus.PENDING_APPROVAL, status));
	}

	private void announcement(String code, AnnouncementScope scope, Department dept, Instant published,
			Instant expires) {
		announcements.save(new Announcement(code, "Title " + code, "Body", "en", scope, dept, published, expires));
	}

	/** Flush to the database and detach everything, so assertions read real rows, not cached objects. */
	private void clearContext() {
		em.flush();
		em.clear();
	}
}
