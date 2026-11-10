package com.mimocodes.hrisrag.data.announcement.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mimocodes.hrisrag.data.announcement.entity.Announcement;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

	/** Announcements visible to a department at {@code now}: company-wide plus that department's, not expired. */
	@Query("""
			select a from Announcement a left join a.department d
			where a.publishedAt <= :now
			  and (a.expiresAt is null or a.expiresAt > :now)
			  and (a.scope = com.mimocodes.hrisrag.data.announcement.entity.AnnouncementScope.COMPANY
			       or d.code = :departmentCode)
			order by a.publishedAt desc
			""")
	List<Announcement> findVisibleTo(@Param("departmentCode") String departmentCode, @Param("now") Instant now);
}
