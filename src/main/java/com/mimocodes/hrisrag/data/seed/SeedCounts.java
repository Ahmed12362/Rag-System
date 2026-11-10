package com.mimocodes.hrisrag.data.seed;

/**
 * Target sizes for one synthetic dataset. Employee count includes the fixed benchmark anchors.
 * Attendance is expressed as a number of recent workdays per employee (rows = active employees × workdays).
 */
public record SeedCounts(
		int departments,
		int employees,
		int attendanceWorkdays,
		int leaveRequests,
		int meetings,
		int rewards,
		int disciplinaryRecords,
		int announcements) {

	public SeedCounts {
		if (departments < 3) {
			throw new IllegalArgumentException("departments must be >= 3 (HR, Engineering and Finance are required by anchors)");
		}
		if (employees < AnchorScenario.ANCHOR_EMPLOYEE_COUNT + 2 * departments) {
			throw new IllegalArgumentException("employees must allow at least 2 generated employees per department plus anchors");
		}
		if (attendanceWorkdays < 1 || leaveRequests < 0 || meetings < 0 || rewards < 0 || disciplinaryRecords < 0
				|| announcements < 0) {
			throw new IllegalArgumentException("counts must be non-negative and attendanceWorkdays >= 1");
		}
	}

	/** Returns a copy where each non-null override replaces the corresponding value. */
	public SeedCounts withOverrides(SeedProperties.Overrides o) {
		if (o == null) {
			return this;
		}
		return new SeedCounts(
				o.departments() != null ? o.departments() : departments,
				o.employees() != null ? o.employees() : employees,
				o.attendanceWorkdays() != null ? o.attendanceWorkdays() : attendanceWorkdays,
				o.leaveRequests() != null ? o.leaveRequests() : leaveRequests,
				o.meetings() != null ? o.meetings() : meetings,
				o.rewards() != null ? o.rewards() : rewards,
				o.disciplinaryRecords() != null ? o.disciplinaryRecords() : disciplinaryRecords,
				o.announcements() != null ? o.announcements() : announcements);
	}
}
