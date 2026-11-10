package com.mimocodes.hrisrag.data.leave.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.leave.entity.LeaveType;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, Long> {

	Optional<LeaveType> findByCode(String code);
}
