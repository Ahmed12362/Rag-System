package com.mimocodes.hrisrag.data.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.mimocodes.hrisrag.data.seed.SeedDataset.EmployeeRow;

/** Pure generator tests: no database. */
class SyntheticDataGeneratorTest {

	private final SyntheticDataGenerator generator = new SyntheticDataGenerator();

	@Test
	void sameSpecProducesIdenticalDataset() {
		SeedDataset a = generator.generate(SeedSpec.of(42, SeedProfile.TEST));
		SeedDataset b = generator.generate(SeedSpec.of(42, SeedProfile.TEST));

		assertThat(b.employees).isEqualTo(a.employees);
		assertThat(b.leaveRequests).isEqualTo(a.leaveRequests);
		assertThat(b.leaveBalances).isEqualTo(a.leaveBalances);
		assertThat(b.attendance).isEqualTo(a.attendance);
		assertThat(b.meetings).isEqualTo(a.meetings);
		assertThat(b.invitations).isEqualTo(a.invitations);
		assertThat(b.rewards).isEqualTo(a.rewards);
		assertThat(b.disciplinary).isEqualTo(a.disciplinary);
		assertThat(b.announcements).isEqualTo(a.announcements);
		assertThat(b.compensations).isEqualTo(a.compensations);
	}

	@Test
	void differentSeedChangesRandomDataButNotAnchors() {
		SeedDataset a = generator.generate(SeedSpec.of(42, SeedProfile.TEST));
		SeedDataset b = generator.generate(SeedSpec.of(7, SeedProfile.TEST));

		assertThat(b.employees).isNotEqualTo(a.employees);
		assertThat(b.employees.subList(0, AnchorScenario.ANCHOR_EMPLOYEE_COUNT))
				.isEqualTo(a.employees.subList(0, AnchorScenario.ANCHOR_EMPLOYEE_COUNT));
	}

	@Test
	void profileTargetsAreMet() {
		SeedSpec spec = SeedSpec.of(42, SeedProfile.STANDARD);
		SeedDataset ds = generator.generate(spec);

		assertThat(ds.departments).hasSize(10);
		assertThat(ds.employees).hasSize(500);
		assertThat(ds.meetings).hasSize(500);
		assertThat(ds.announcements).hasSize(100);
		assertThat(ds.leaveRequests.size()).isGreaterThanOrEqualTo((int) (1_000 * SeedValidator.MIN_TARGET_SHARE));
		assertThat(ds.rewards.size()).isGreaterThanOrEqualTo((int) (300 * SeedValidator.MIN_TARGET_SHARE));
		// ~ active employees x 10 workdays
		assertThat(ds.attendance.size()).isBetween(4_000, 5_200);
	}

	@Test
	void anchorsAreTheFirstEmployeesAndRandomActivityNeverReferencesThem() {
		SeedDataset ds = generator.generate(SeedSpec.of(42, SeedProfile.TEST));
		assertThat(ds.employees.subList(0, 5)).extracting(EmployeeRow::code)
				.containsExactly("E-1000", "E-1001", "E-1002", "E-1003", "E-1004");

		Set<Long> anchorIds = Set.of(1L, 2L, 3L, 4L, 5L);
		assertThat(ds.employees.stream().filter(e -> !e.anchor()).map(EmployeeRow::managerId)).doesNotContainAnyElementsOf(anchorIds);
		assertThat(ds.leaveRequests.stream().filter(r -> anchorIds.contains(r.employeeId())).map(r -> r.code()))
				.allMatch(code -> code.startsWith("LR-100"));
		Set<Long> anchorMeetingIds = ds.meetings.stream().filter(m -> m.code().startsWith("M-100"))
				.map(m -> m.id()).collect(Collectors.toSet());
		assertThat(ds.invitations.stream().filter(i -> anchorIds.contains(i.employeeId())))
				.allMatch(i -> anchorMeetingIds.contains(i.meetingId()));
	}

	@Test
	void countsAreValidated() {
		assertThatThrownBy(() -> new SeedCounts(2, 30, 20, 60, 20, 15, 5, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new SeedCounts(3, 8, 20, 60, 20, 15, 5, 10))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void workCalendarFollowsEgyptianWeekAndHolidays() {
		assertThat(WorkCalendar.isWorkday(LocalDate.of(2026, 11, 1))).isTrue();   // Sunday
		assertThat(WorkCalendar.isWorkday(LocalDate.of(2026, 10, 30))).isFalse(); // Friday
		assertThat(WorkCalendar.isWorkday(LocalDate.of(2026, 10, 6))).isFalse();  // Armed Forces Day
		// LR-1000: 6 workdays from Sunday 2 Aug 2026 -> Sun..Thu + next Sunday
		assertThat(WorkCalendar.endAfterWorkdays(LocalDate.of(2026, 8, 2), 6)).isEqualTo(LocalDate.of(2026, 8, 9));
		assertThat(WorkCalendar.workdaysBetween(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 9))).isEqualTo(6);
		assertThat(WorkCalendar.workdaysBefore(LocalDate.of(2026, 11, 1), 20))
				.hasSize(20).first().isEqualTo(LocalDate.of(2026, 10, 1));
	}
}
