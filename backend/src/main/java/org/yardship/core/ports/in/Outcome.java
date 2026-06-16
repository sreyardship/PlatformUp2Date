package org.yardship.core.ports.in;

/**
 * The result kind of REQUESTING a manual scrape.
 *
 * <ul>
 *   <li>{@code SCRAPED} — this request won the scrape lock and performed a fresh scrape,
 *       writing a new snapshot (which resets the staleness clock).</li>
 *   <li>{@code RATE_LIMITED} — the manual-scrape budget for the current window was exhausted;
 *       no scrape happened. Not produced until slice 04 introduces the budget.</li>
 *   <li>{@code IN_PROGRESS} — another replica already holds the scrape lock, so this request
 *       did not scrape; a scrape is already underway.</li>
 * </ul>
 */
public enum Outcome {
    SCRAPED,
    RATE_LIMITED,
    IN_PROGRESS
}
