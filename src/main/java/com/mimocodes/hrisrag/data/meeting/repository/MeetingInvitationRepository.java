package com.mimocodes.hrisrag.data.meeting.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mimocodes.hrisrag.data.meeting.entity.Meeting;
import com.mimocodes.hrisrag.data.meeting.entity.MeetingInvitation;
import com.mimocodes.hrisrag.data.meeting.entity.MeetingInvitationId;

public interface MeetingInvitationRepository extends JpaRepository<MeetingInvitation, MeetingInvitationId> {

	/**
	 * Backs the "next meetings for employee X" tool (M8): meetings the employee is invited to that start after
	 * {@code now}, excluding cancelled meetings and declined invitations, soonest first.
	 */
	@Query("""
			select i.meeting from MeetingInvitation i
			where i.employee.employeeCode = :employeeCode
			  and i.meeting.startsAt > :now
			  and i.meeting.status <> com.mimocodes.hrisrag.data.meeting.entity.MeetingStatus.CANCELLED
			  and i.response <> com.mimocodes.hrisrag.data.meeting.entity.InvitationResponse.DECLINED
			order by i.meeting.startsAt
			""")
	List<Meeting> findUpcomingMeetings(@Param("employeeCode") String employeeCode, @Param("now") Instant now);
}
