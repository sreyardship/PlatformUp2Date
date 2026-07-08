package org.yardship.core.ports.out;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier selecting the {@link ScrapeRateLimiter} bean backing the full-fleet scrape's
 * rolling-window budget (Valkey ZSET {@code scrape:budget}, configured from
 * {@code platform-config.scrape-trigger}). Spent by the UI's Refresh trigger and the automatic
 * staleness scrape's manual-trigger path — see
 * {@link org.yardship.core.services.ApplicationVersionService}.
 *
 * <p>Distinct from {@link TargetedScrapeBudget} so agent-driven targeted-scrape traffic cannot starve
 * the UI's full-Refresh budget (issue 03).
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface FullScrapeBudget {
}
