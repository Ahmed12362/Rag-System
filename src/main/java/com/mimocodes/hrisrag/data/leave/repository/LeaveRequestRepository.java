package com.mimocodes.hrisrag.data.leave.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.leave.entity.LeaveRequest;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

	/** Backs the "status of leave request LR-xxxx" tool (M8). */
	Optional<LeaveRequest> findByRequestCode(String requestCode);

	List<LeaveRequest> findByEmployee_EmployeeCodeOrderByStartDateDesc(String employeeCode);
}
