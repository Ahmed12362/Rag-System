package com.mimocodes.hrisrag.data.attendance.entity;

/** Must match the CHECK constraint on attendance_record.status (V2). */
public enum AttendanceStatus {
	PRESENT, LATE, ABSENT, REMOTE, ON_LEAVE, HOLIDAY
}
