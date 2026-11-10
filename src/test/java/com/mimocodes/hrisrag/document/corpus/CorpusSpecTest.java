package com.mimocodes.hrisrag.document.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mimocodes.hrisrag.data.seed.SeedDataset;
import com.mimocodes.hrisrag.data.seed.SeedProfile;
import com.mimocodes.hrisrag.data.seed.SeedSpec;
import com.mimocodes.hrisrag.data.seed.SyntheticDataGenerator;
import com.mimocodes.hrisrag.data.seed.WorkCalendar;

/** The committed corpus-spec must be consistent and must agree with the synthetic HRIS data (D21). */
class CorpusSpecTest {

	static CorpusSpec spec;

	@BeforeAll
	static void load() {
		spec = CorpusSpec.load(Path.of("corpus-spec"));
	}

	@Test
	void specIsConsistent() {
		assertThat(spec.validate()).as("spec errors").isEmpty();
	}

	@Test
	void inventoryMatchesDesign() {
		assertThat(spec.countsByGroup()).hasSize(10).containsAllEntriesOf(Map.of(
				"CORE", 24, "SUPERSEDED", 6, "PROCEDURE", 31, "FAQ", 52, "HANDBOOK", 40, "DEPARTMENT", 30,
				"CONFIDENTIAL", 14, "DRAFT", 5, "DISTRACTOR", 20, "INJECTION", 5));
		assertThat(spec.documents()).hasSize(227);
		assertThat(spec.countsByLanguage().get("en") + spec.countsByLanguage().get("ar")).isEqualTo(227);
		assertThat(spec.documents().stream().filter(PlannedDocument::egyptian).count()).isEqualTo(13);
	}

	@Test
	void pilotCoversEveryGroupInBothLanguagesPlusEgyptian() {
		var pilot = spec.pilotSelection();
		assertThat(pilot).hasSize(21);
		CorpusSpec.GROUPS.forEach(g -> {
			assertThat(pilot).anyMatch(d -> d.group().equals(g) && d.metadata().language().equals("en"));
			assertThat(pilot).anyMatch(d -> d.group().equals(g) && d.metadata().language().equals("ar"));
		});
		assertThat(pilot).anyMatch(PlannedDocument::egyptian);
	}

	@Test
	void frontMatterRoundTripsForEveryPlannedDocument() {
		spec.documents().forEach(d -> assertThat(DocumentMetadata.parse(d.metadata().toFrontMatter() + "# x\n").metadata())
				.as(d.id()).isEqualTo(d.metadata()));
	}

	@Test
	void d21FactsMatchTheHrisDataGenerator() {
		Map<String, Fact> f = spec.facts();
		assertThat(f.get("LATE_GRACE_MINUTES").value()).isEqualTo(String.valueOf(WorkCalendar.LATE_GRACE_MINUTES));
		assertThat(f.get("WORK_START_TIME").value()).isEqualTo(WorkCalendar.WORKDAY_START.toString());
		assertThat(f.get("WORK_END_TIME").value()).isEqualTo(WorkCalendar.WORKDAY_END.toString());

		SeedDataset ds = new SyntheticDataGenerator().generate(SeedSpec.of(42, SeedProfile.STANDARD));
		assertThat(ds.leaveType("ANNUAL").defaultAnnualDays()).isEqualByComparingTo(f.get("ANNUAL_LEAVE_DAYS").value());
		assertThat(ds.leaveType("SICK").defaultAnnualDays()).isEqualByComparingTo(f.get("SICK_LEAVE_DAYS").value());
		assertThat(ds.leaveType("CASUAL").defaultAnnualDays()).isEqualByComparingTo(f.get("CASUAL_LEAVE_DAYS").value());

		// Long-service rule: 30 days exactly for employees with >= 10 full years at 1 January of the leave year.
		long annualTypeId = ds.leaveType("ANNUAL").id();
		LocalDate yearStart = LocalDate.of(2026, 1, 1);
		int longService = Integer.parseInt(f.get("LONG_SERVICE_YEARS").value());
		BigDecimal longDays = new BigDecimal(f.get("ANNUAL_LEAVE_LONG_SERVICE_DAYS").value());
		ds.leaveBalances.stream().filter(b -> b.leaveTypeId() == annualTypeId)
				.filter(b -> !ds.employee(b.employeeId()).anchor())
				.forEach(b -> {
					boolean isLong = ChronoUnit.YEARS.between(ds.employee(b.employeeId()).hireDate(), yearStart) >= longService;
					assertThat(b.entitledDays().compareTo(longDays) == 0).as("employee " + b.employeeId()).isEqualTo(isLong);
				});
		// Casual leave is always 1 day per request; sick leave is reported within 2 days.
		long casualTypeId = ds.leaveType("CASUAL").id();
		assertThat(ds.leaveRequests.stream().filter(r -> r.leaveTypeId() == casualTypeId))
				.allMatch(r -> r.days().compareTo(new BigDecimal(f.get("CASUAL_LEAVE_PER_REQUEST").value())) == 0);
	}
}
