package org.yardship.core.ports.out;

import jakarta.inject.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * CDI qualifier selecting the {@link ScrapeRateLimiter} bean backing the targeted-scrape
 * rolling-window budget (Valkey ZSET {@code scrape:targeted:budget}, configured from
 * {@code platform-config.targeted-scrape-trigger}). Spent only by
 * {@link org.yardship.core.services.ApplicationVersionService#targetedScrape}, deliberately sized
 * larger than {@link FullScrapeBudget} (default 30/1h vs 10/1h) so agent-driven update work has its
 * own headroom and cannot starve the UI's full-Refresh budget (issue 03).
 */
@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.TYPE})
public @interface TargetedScrapeBudget {
}
