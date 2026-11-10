package com.mimocodes.hrisrag.data.leave.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "leave_type")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LeaveType {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** e.g. ANNUAL, SICK, UNPAID. */
	@Column(nullable = false, unique = true, length = 20)
	private String code;

	@Column(name = "name_en", nullable = false, length = 80)
	private String nameEn;

	@Column(name = "name_ar", nullable = false, length = 80)
	private String nameAr;

	@Column(name = "default_annual_days", nullable = false, precision = 5, scale = 1)
	private BigDecimal defaultAnnualDays;

	public LeaveType(String code, String nameEn, String nameAr, BigDecimal defaultAnnualDays) {
		this.code = code;
		this.nameEn = nameEn;
		this.nameAr = nameAr;
		this.defaultAnnualDays = defaultAnnualDays;
	}
}
