package com.mimocodes.hrisrag.data.employee.entity;

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
@Table(name = "department")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Department {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 20)
	private String code;

	@Column(name = "name_en", nullable = false, length = 120)
	private String nameEn;

	@Column(name = "name_ar", nullable = false, length = 120)
	private String nameAr;

	public Department(String code, String nameEn, String nameAr) {
		this.code = code;
		this.nameEn = nameEn;
		this.nameAr = nameAr;
	}
}
