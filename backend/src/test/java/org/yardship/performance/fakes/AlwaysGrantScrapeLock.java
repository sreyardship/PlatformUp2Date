package org.yardship.performance.fakes;

import org.yardship.core.ports.out.ScrapeLock;

/**
 * In-memory fake for {@link ScrapeLock} used in the performance harness.
 *
 * <p>{@link #tryAcquire()} always returns {@code true} (the harness is always the sole driver),
 * and {@link #release()} is a no-op.
 */
public class AlwaysGrantScrapeLock implements ScrapeLock {

    @Override
    public boolean tryAcquire() {
        return true;
    }

    @Override
    public void release() {
        // no-op: no lock to release in-memory
    }
}
