package com.mimocodes.hrisrag.data.meeting.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.meeting.entity.Meeting;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {

	Optional<Meeting> findByMeetingCode(String meetingCode);
}
