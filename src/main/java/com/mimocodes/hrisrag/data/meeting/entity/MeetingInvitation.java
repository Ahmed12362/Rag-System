package com.mimocodes.hrisrag.data.meeting.entity;

import com.mimocodes.hrisrag.data.employee.entity.Employee;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "meeting_invitation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingInvitation {

	@EmbeddedId
	private MeetingInvitationId id;

	@MapsId("meetingId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "meeting_id")
	private Meeting meeting;

	@MapsId("employeeId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id")
	private Employee employee;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private InvitationResponse response;

	@Column
	private Boolean attended;

	public MeetingInvitation(Meeting meeting, Employee employee, InvitationResponse response, Boolean attended) {
		this.id = new MeetingInvitationId(meeting.getId(), employee.getId());
		this.meeting = meeting;
		this.employee = employee;
		this.response = response;
		this.attended = attended;
	}
}
