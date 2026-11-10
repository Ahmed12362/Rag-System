package com.mimocodes.hrisrag.benchmark;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.mimocodes.hrisrag.IntegrationTestBase;
import com.mimocodes.hrisrag.data.attendance.entity.AttendanceStatus;
import com.mimocodes.hrisrag.data.attendance.repository.AttendanceRecordRepository;
import com.mimocodes.hrisrag.data.leave.entity.LeaveRequest;
import com.mimocodes.hrisrag.data.leave.repository.LeaveBalanceRepository;
import com.mimocodes.hrisrag.data.leave.repository.LeaveRequestRepository;
import com.mimocodes.hrisrag.data.meeting.entity.Meeting;
import com.mimocodes.hrisrag.data.meeting.repository.MeetingInvitationRepository;
import com.mimocodes.hrisrag.data.seed.SeedProfile;
import com.mimocodes.hrisrag.data.seed.SeedService;
import com.mimocodes.hrisrag.data.seed.SeedSpec;
import com.mimocodes.hrisrag.data.seed.WorkCalendar;

/**
 * Every STRUCTURED expected answer is recomputed from the seeded HRIS database (the values a correct tool would return)
 * and must match the dataset's must_contain labels. Runs in a rolled-back transaction.
 */
@Transactional
class BenchmarkStructuredAnswersTest extends IntegrationTestBase {

	@Autowired SeedService seed;
	@Autowired LeaveBalanceRepository balances;
	@Autowired LeaveRequestRepository requests;
	@Autowired MeetingInvitationRepository invitations;
	@Autowired AttendanceRecordRepository attendance;

	static BenchmarkDataset ds;

	@BeforeAll
	static void load() {
		ds = BenchmarkDataset.load(Path.of("benchmark/hris-bench-v1.yaml"));
	}

	@Test
	void structuredLabelsMatchTheDatabase() {
		seed.reseed(SeedSpec.of(42, SeedProfile.TEST));
		Instant now = ds.referenceDate().atStartOfDay(WorkCalendar.ZONE).toInstant();
		var cases = ds.cases().stream().filter(c -> c.category().equals("STRUCTURED")).toList();
		assertThat(cases).hasSize(18);

		for (BenchmarkCase c : cases) {
			Set<String> truth = new HashSet<>();
			var a = c.toolArgs();
			switch (c.expectedTool()) {
				case "get_leave_balance" -> truth.add(plain(balances.findByEmployee_EmployeeCodeAndLeaveType_CodeAndYear(
						a.get("employee"), a.get("leave_type"), Short.parseShort(a.get("year"))).orElseThrow().remainingDays()));
				case "get_leave_request_status" -> {
					LeaveRequest r = requests.findByRequestCode(a.get("request_code")).orElseThrow();
					truth.add(r.getStatus().name());
					truth.add(r.getStartDate().toString());
				}
				case "get_upcoming_meetings" -> {
					var meetings = invitations.findUpcomingMeetings(a.get("employee"), now);
					meetings.forEach(m -> {
						truth.add(m.getMeetingCode());
						truth.add(m.getTitle());
					});
					Meeting next = meetings.getFirst();
					truth.add(LocalDate.ofInstant(next.getStartsAt(), WorkCalendar.ZONE).toString());
				}
				case "get_attendance_summary" -> {
					YearMonth month = YearMonth.parse(a.get("month"));
					truth.add(String.valueOf(attendance.findByEmployee_EmployeeCodeAndWorkDateBetweenOrderByWorkDate(
							a.get("employee"), month.atDay(1), month.atEndOfMonth()).stream()
							.filter(r -> r.getStatus() == AttendanceStatus.LATE).count()));
				}
				default -> throw new AssertionError(c.id() + ": unexpected tool " + c.expectedTool());
			}
			Set<String> truthLower = new HashSet<>();
			truth.forEach(t -> truthLower.add(t.toLowerCase(Locale.ROOT)));
			for (String item : c.mustContain()) {
				assertThat(Arrays.stream(item.split("\\|")).anyMatch(alt -> truthLower.contains(alt.toLowerCase(Locale.ROOT))))
						.as("%s: must_contain '%s' vs database truth %s", c.id(), item, truth).isTrue();
			}
		}
	}

	/** 13.0 → "13", 0.0 → "0" (how a number appears in an answer). */
	private static String plain(BigDecimal v) {
		return v.stripTrailingZeros().toPlainString();
	}
}
