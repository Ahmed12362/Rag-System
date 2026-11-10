package com.mimocodes.hrisrag.data.employee.entity;

import java.time.LocalDate;

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
@Table(name = "employee_reward")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmployeeReward {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "employee_id", nullable = false)
	private Employee employee;

	@Enumerated(EnumType.STRING)
	@Column(name = "reward_type", nullable = false, length = 30)
	private RewardType rewardType;

	@Column(name = "awarded_on", nullable = false)
	private LocalDate awardedOn;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "awarded_by_id")
	private Employee awardedBy;

	@Column(nullable = false, length = 500)
	private String description;

	public EmployeeReward(Employee employee, RewardType rewardType, LocalDate awardedOn, Employee awardedBy,
			String description) {
		this.employee = employee;
		this.rewardType = rewardType;
		this.awardedOn = awardedOn;
		this.awardedBy = awardedBy;
		this.description = description;
	}
}
