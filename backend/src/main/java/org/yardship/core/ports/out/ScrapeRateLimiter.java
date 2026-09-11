package org.yardship.core.ports.out;

import java.time.Instant;

/**
 * Outbound port for a cluster-wide, rolling-window budget that caps manual scrapes.
 *
 * <p>The window is a sliding interval of fixed length; at most {@code max-per-window} triggers may
 * be admitted within any such interval. Backed by a Valkey ZSET of trigger timestamps evaluated by a
 * single atomic Lua script: evict entries older than {@code now - window}, count the rest, and if
 * under the cap, add {@code now} and report the remaining budget; otherwise report the
 * {@code retryAfter} derived from the oldest in-window entry (when that entry ages out a slot frees).
 *
 * <p>{@code now} is supplied by the caller (from the app's {@link java.time.Clock}) so the window is
 * deterministic in tests.
 *
 * <p>The core never imports Valkey/Redis types — only this port. {@link Decision} and {@link Budget}
 * hold plain ints/longs, no infrastructure.
 */
public interface ScrapeRateLimiter {

    /**
     * Attempt to spend one slot from the rolling-window budget as of {@code now}, atomically.
     *
     * <p>When admitted, the slot IS consumed (a timestamp is recorded) — callers must only invoke
     * this once they have decided to scrape.
     *
     * @param now the current instant (from the app clock).
     * @return a {@link Decision}: when {@link Decision#allowed()} the slot was consumed and
     *         {@link Decision#remaining()} / {@link Decision#windowResetsIn()} describe the budget
     *         after this trigger; when not allowed no slot was consumed and
     *         {@link Decision#retryAfter()} is the seconds until the oldest in-window entry ages out.
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    Decision tryAcquire(Instant now);

    /**
     * Read the current budget as of {@code now} WITHOUT consuming a slot (evicts aged entries for the
     * count but adds nothing).
     *
     * @param now the current instant (from the app clock).
     * @return the remaining budget and seconds until the window frees a slot.
     * @throws RuntimeException if the backing store is unreachable (fail closed).
     */
    Budget peek(Instant now);

    /**
     * The outcome of a {@link #tryAcquire(Instant)} attempt.
     *
     * @param allowed        whether a slot was consumed (the trigger may proceed).
     * @param remaining      slots left in the window AFTER this trigger (0 when not allowed).
     * @param windowResetsIn seconds until the window frees a slot (0 when the window is empty).
     * @param retryAfter     seconds to wait before retrying (0 when allowed; positive when rejected).
     */
    record Decision(boolean allowed, int remaining, long windowResetsIn, long retryAfter) {

        public static Decision allowed(int remaining, long windowResetsIn) {
            return new Decision(true, remaining, windowResetsIn, 0);
        }

        public static Decision rejected(long retryAfter) {
            return new Decision(false, 0, 0, retryAfter);
        }
    }

    /**
     * A read-only view of the budget as of some instant.
     *
     * @param remaining      slots left in the window.
     * @param windowResetsIn seconds until the window frees a slot (0 when the window is empty).
     */
    record Budget(int remaining, long windowResetsIn) {
    }
}
