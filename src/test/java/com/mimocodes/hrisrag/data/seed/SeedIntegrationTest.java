package com.mimocodes.hrisrag.data.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mimocodes.hrisrag.IntegrationTestBase;
import com.mimocodes.hrisrag.data.employee.entity.Department;
import com.mimocodes.hrisrag.data.employee.repository.DepartmentRepository;
import com.mimocodes.hrisrag.data.leave.repository.LeaveBalanceRepository;
import com.mimocodes.hrisrag.data.leave.repository.LeaveRequestRepository;
import com.mimocodes.hrisrag.data.leave.entity.LeaveRequestStatus;
import com.mimocodes.hrisrag.data.meeting.entity.Meeting;
import com.mimocodes.hrisrag.data.meeting.repository.MeetingInvitationRepository;

/** Seeds the real Flyway schema and checks validation, reproducibility and the anchors through the JPA layer. */
class SeedIntegrationTest extends IntegrationTestBase {

	@Autowired SeedService seedService;
	@Autowired DatasetFingerprint fingerprint;
	@Autowired JdbcTemplate jdbc;
	@Autowired LeaveBalanceRepository balances;
	@Autowired LeaveRequestRepository requests;
	@Autowired MeetingInvitationRepository invitations;
	@Autowired DepartmentRepository departments;

	@Test
	void testProfileSeedsAndPassesValidation() {
		SeedService.Result r = seedService.reseed(SeedSpec.of(42, SeedProfile.TEST));

		assertThat(r.report().failures()).as("validation failures").isEmpty();
		assertThat(r.report().tableCounts().get("employee")).isEqualTo(30L);
		assertThat(r.report().tableCounts().get("department")).isEqualTo(3L);
	}

	@Test
	void sameSeedReproducesIdenticalDatabaseContent() {
		seedService.reseed(SeedSpec.of(42, SeedProfile.TEST));
		String first = fingerprint.combined();
		seedService.reseed(SeedSpec.of(42, SeedProfile.TEST));
		String second = fingerprint.combined();
		seedService.reseed(SeedSpec.of(7, SeedProfile.TEST));
		String otherSeed = fingerprint.combined();

		assertThat(second).isEqualTo(first);
		assertThat(otherSeed).isNotEqualTo(first);
	}

	@Test
	void anchorAnswersReadableThroughRepositories() {
		seedService.reseed(SeedSpec.of(42, SeedProfile.TEST));

		assertThat(balances.findByEmployee_EmployeeCodeAndLeaveType_CodeAndYear("E-1001", "ANNUAL", (short) 2026)
				.orElseThrow().remainingDays()).isEqualByComparingTo(AnchorScenario.EMPLOYEE_ANNUAL_REMAINING);
		assertThat(requests.findByRequestCode(AnchorScenario.PENDING_REQUEST).orElseThrow().getStatus())
				.isEqualTo(LeaveRequestStatus.PENDING);
		Instant reference = LocalDate.parse(AnchorScenario.REFERENCE_DATE_TEXT).atStartOfDay(WorkCalendar.ZONE).toInstant();
		assertThat(invitations.findUpcomingMeetings("E-1001", reference)).extracting(Meeting::getMeetingCode)
				.containsExactlyElementsOf(AnchorScenario.EMPLOYEE_UPCOMING_MEETINGS);
	}

	@Test
	void sequencesAdvancedSoJpaInsertsDoNotCollide() {
		seedService.reseed(SeedSpec.of(42, SeedProfile.TEST));
		Department created = departments.saveAndFlush(new Department("D-NEW", "New", "جديد"));

		assertThat(created.getId()).isGreaterThan(3L);
		departments.delete(created);
	}
}
