package com.mimocodes.hrisrag.tools;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code hrisrag.tools.*}.
 *
 * @param referenceDate the synthetic dataset's "today" (decision D20). "This year", "upcoming" and the default month are
 *                      computed from it, not from the real clock, so the fixed benchmark anchors stay valid
 * @param zone          the company time zone
 * @param maxMeetings   cap on meetings returned by get_upcoming_meetings
 */
@ConfigurationProperties(prefix = "hrisrag.tools")
public record ToolsProperties(
		@DefaultValue("2026-11-01") LocalDate referenceDate,
		@DefaultValue("Africa/Cairo") ZoneId zone,
		@DefaultValue("5") int maxMeetings) {
}
