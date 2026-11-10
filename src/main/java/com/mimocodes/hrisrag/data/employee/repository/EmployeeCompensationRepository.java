package com.mimocodes.hrisrag.data.employee.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.employee.entity.EmployeeCompensation;

/** RESTRICTED: access only through a service that enforces HR_ADMIN authorization (M8). */
public interface EmployeeCompensationRepository extends JpaRepository<EmployeeCompensation, Long> {
}
