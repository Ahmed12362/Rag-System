package com.mimocodes.hrisrag.data.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Synthetic company calendar modelled on Egypt: Sunday–Thursday work week, Friday/Saturday weekend, and public holidays.
 * Islamic holiday dates are approximations (they follow the lunar calendar). This is a synthetic calendar, not an
 * official one. Policy documents (M3) must state the same rules.
 */
public final class WorkCalendar {

	public static final ZoneId ZONE = ZoneId.of("Africa/Cairo");
	public static final LocalTime WORKDAY_START = LocalTime.of(9, 0);
	public static final LocalTime WORKDAY_END = LocalTime.of(17, 0);
	/** Arrivals up to this many minutes after start are not counted as LATE (attendance policy grace period). */
	public static final int LATE_GRACE_MINUTES = 15;

	private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
			MonthDay.of(1, 7),   // Coptic Christmas
			MonthDay.of(1, 25),  // Revolution / Police Day
			MonthDay.of(4, 25),  // Sinai Liberation Day
			MonthDay.of(5, 1),   // Labour Day
			MonthDay.of(6, 30),  // June 30 Revolution
			MonthDay.of(7, 23),  // July 23 Revolution
			MonthDay.of(10, 6)); // Armed Forces Day

	/** Approximate moveable holidays (Eid al-Fitr, Sham el-Nessim, Eid al-Adha, Islamic New Year, Prophet's Birthday). */
	private static final Set<LocalDate> MOVEABLE_HOLIDAYS = Set.of(
			LocalDate.of(2025, 3, 30), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 4, 1),
			LocalDate.of(2025, 4, 21),
			LocalDate.of(2025, 6, 5), LocalDate.of(2025, 6, 6), LocalDate.of(2025, 6, 7), LocalDate.of(2025, 6, 8),
			LocalDate.of(2025, 6, 26),
			LocalDate.of(2025, 9, 4),
			LocalDate.of(2026, 3, 20), LocalDate.of(2026, 3, 21), LocalDate.of(2026, 3, 22),
			LocalDate.of(2026, 4, 13),
			LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 27), LocalDate.of(2026, 5, 28), LocalDate.of(2026, 5, 29),
			LocalDate.of(2026, 6, 16),
			LocalDate.of(2026, 8, 25));

	private WorkCalendar() {
	}

	public static boolean isWeekend(LocalDate d) {
		return d.getDayOfWeek() == DayOfWeek.FRIDAY || d.getDayOfWeek() == DayOfWeek.SATURDAY;
	}

	public static boolean isHoliday(LocalDate d) {
		return FIXED_HOLIDAYS.contains(MonthDay.from(d)) || MOVEABLE_HOLIDAYS.contains(d);
	}

	public static boolean isWorkday(LocalDate d) {
		return !isWeekend(d) && !isHoliday(d);
	}

	/** First workday on or after {@code d}. */
	public static LocalDate onOrAfter(LocalDate d) {
		LocalDate x = d;
		while (!isWorkday(x)) {
			x = x.plusDays(1);
		}
		return x;
	}

	/** End date of a leave that starts on workday {@code start} and covers exactly {@code workdays} workdays. */
	public static LocalDate endAfterWorkdays(LocalDate start, int workdays) {
		if (!isWorkday(start) || workdays < 1) {
			throw new IllegalArgumentException("start must be a workday and workdays >= 1");
		}
		LocalDate end = start;
		int counted = 1;
		while (counted < workdays) {
			end = end.plusDays(1);
			if (isWorkday(end)) {
				counted++;
			}
		}
		return end;
	}

	/** Number of workdays in [start, end] inclusive. */
	public static int workdaysBetween(LocalDate start, LocalDate end) {
		int n = 0;
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			if (isWorkday(d)) {
				n++;
			}
		}
		return n;
	}

	/** The {@code n} most recent workdays strictly before {@code reference}, oldest first. */
	public static List<LocalDate> workdaysBefore(LocalDate reference, int n) {
		List<LocalDate> days = new ArrayList<>(n);
		LocalDate d = reference.minusDays(1);
		while (days.size() < n) {
			if (isWorkday(d)) {
				days.add(d);
			}
			d = d.minusDays(1);
		}
		return days.reversed();
	}
}
