package com.mimocodes.hrisrag.data.meeting.entity;

import java.time.Instant;

import com.mimocodes.hrisrag.data.employee.entity.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "meeting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Stable business key, e.g. M-0042. */
	@Column(name = "meeting_code", nullable = false, unique = true, length = 20)
	private String meetingCode;

	@Column(nullable = false, length = 200)
	private String title;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organizer_id", nullable = false)
	private Employee organizer;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(name = "ends_at", nullable = false)
	private Instant endsAt;

	@Column(length = 120)
	private String location;

	@Column(name = "requires_approval", nullable = false)
	private boolean requiresApproval;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MeetingStatus status;

	public Meeting(String meetingCode, String title, Employee organizer, Instant startsAt, Instant endsAt,
			String location, boolean requiresApproval, MeetingStatus status) {
		this.meetingCode = meetingCode;
		this.title = title;
		this.organizer = organizer;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.location = location;
		this.requiresApproval = requiresApproval;
		this.status = status;
	}
}
