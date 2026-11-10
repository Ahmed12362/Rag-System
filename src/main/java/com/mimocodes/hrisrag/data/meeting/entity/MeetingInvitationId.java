package com.mimocodes.hrisrag.data.meeting.entity;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Composite key (meeting_id, employee_id). A record gives correct equals/hashCode for free. */
@Embeddable
public record MeetingInvitationId(
		@Column(name = "meeting_id") Long meetingId,
		@Column(name = "employee_id") Long employeeId) implements Serializable {
}
