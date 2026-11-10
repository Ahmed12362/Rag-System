package com.mimocodes.hrisrag.data.employee.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mimocodes.hrisrag.data.employee.entity.EmployeeReward;

public interface EmployeeRewardRepository extends JpaRepository<EmployeeReward, Long> {

	List<EmployeeReward> findByEmployee_EmployeeCodeOrderByAwardedOnDesc(String employeeCode);
}
