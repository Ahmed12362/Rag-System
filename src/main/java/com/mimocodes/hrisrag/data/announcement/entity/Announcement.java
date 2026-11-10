package com.mimocodes.hrisrag.data.announcement.entity;

import java.time.Instant;

import com.mimocodes.hrisrag.data.employee.entity.Department;

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
@Table(name = "announcement")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Announcement {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "announcement_code", nullable = false, unique = true, length = 20)
	private String announcementCode;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String body;

	/** ISO 639-1 code: "en" or "ar". */
	@Column(nullable = false, length = 5)
	private String language;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AnnouncementScope scope;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "department_id")
	private Department department;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "expires_at")
	private Instant expiresAt;

	public Announcement(String announcementCode, String title, String body, String language, AnnouncementScope scope,
			Department department, Instant publishedAt, Instant expiresAt) {
		this.announcementCode = announcementCode;
		this.title = title;
		this.body = body;
		this.language = language;
		this.scope = scope;
		this.department = department;
		this.publishedAt = publishedAt;
		this.expiresAt = expiresAt;
	}
}
