package com.mimocodes.hrisrag.data.meeting.entity;

/** Must match the CHECK constraint on meeting.status (V2). */
public enum MeetingStatus {
	SCHEDULED, PENDING_APPROVAL, CANCELLED, COMPLETED
}
