# The full-scrape loop runs bounded-parallel, capped by a global concurrency limit

A full scrape reads every monitored app's `current` and `latest` version. The
original `ApplicationVersionService.scrape()` did this **sequentially** — iterating
apps one at a time and making two *blocking* network reads per app (`current` then
`latest`) — so the cost was ≈ `2·N` sequential round-trips. With a uniform
per-source latency `D`, an in-repo baseline harness (`ScrapeBaselineHarness`, run
via `gradle perfTest`) made the linear signature visible:

```
sequential (delay=50ms/leg):
N    | median(ms)
-----+-----------
1    |       104
5    |       519
10   |      1035
25   |      2574
50   |      5100
```

This is the UI Refresh / lazy auto-refresh path, and at the design target of
*hundreds* of monitored apps the sequential sum is unacceptable (≈120 s for 300
apps at 200 ms/leg). We therefore **fan the per-app reads out concurrently**, so a
full scrape's wall-clock approaches the slowest app rather than the sum.

## Decision: bounded parallel, not unbounded fan-out

Parallelism is **bounded by a configurable global concurrency cap**
(`platform-config.scrape-concurrency`, default **15**), implemented with **virtual
threads gated by a global `Semaphore(cap)`**: each scrape opens a per-scrape
`Executors.newVirtualThreadPerTaskExecutor()`, submits one task per app, and each
task acquires a permit before its two blocking reads and releases it after. The
blocking-read workload is I/O-bound, which is exactly what virtual threads are for;
the semaphore — not the thread count — is the bound.

The bound is a **hard requirement, not a nicety.** The `latest` legs all hit
GitHub through a single shared `GITHUB_TOKEN` identity. GitHub's secondary rate
limits cap concurrent requests at ~100 and explicitly direct clients to make
requests for one token *serially* rather than concurrently. Unbounded fan-out at
300 apps would burst into 403/429 `Retry-After` responses, which the scrape loop
counts as `failed` — self-inflicting a flaky scrape in exactly the high-`N` case
this work targets. A cap of ~15 keeps roughly 90 %+ of the speedup (≈8 s vs ≈120 s
for 300 apps at 200 ms) while staying comfortably under GitHub's ceiling, and is
trivially retunable from config once real fleets report numbers.

The same harness after the change shows the cap working — flat while `N ≤ cap`,
then stepping once per wave of `ceil(N / cap)`:

```
parallel (cap=15, delay=50ms/leg):
N    | median(ms)   waves = ceil(N/15)
-----+-----------
1    |       103    1
5    |       103    1
10   |       103    1
25   |       206    2
50   |       407    4
```

`103 ms ≈ 2·D` (one app's two sequential legs, all apps overlapping). The step at
`N=25`/`N=50` is the proof the loop is *bounded*: unbounded fan-out would stay flat
at ~103 ms for all `N`.

## Invariants preserved

The execution model changed; the contract did not. The parallel loop preserves
exactly what the sequential loop guaranteed (and the existing
`ScrapeServiceTests` / `TargetedScrapeServiceTests` plus new out-of-order-completion
tests in `ParallelScrapeServiceTests` pin all three):

- **Per-app failure isolation** — each app's read+catch lives inside its own task;
  one source throwing (or an `InterruptedException` on permit acquisition) is
  caught, counted in `failed`, and never aborts the scrape or propagates.
- **`applications.size() + failed == attempted`** — `attempted` is the app count;
  results are tallied from per-index slots.
- **Deterministic, config-order output** — results are written into
  index-positioned slots (`Slot[]`) and assembled by iterating `0..n-1`, *not*
  appended on completion. `applications` and `targetResults` follow config order
  regardless of which task finishes first, so the persisted snapshot does not churn.

## Concurrency model: virtual threads + Semaphore

Considered alternatives:

- **Fixed thread pool sized to the cap** (`newFixedThreadPool(cap)`) — the pool
  size *is* the bound. Rejected: ties the bound to a pre-sized pool of platform
  threads and conflates "how many OS threads" with "how many concurrent upstream
  requests"; virtual threads make the blocking reads cheap and leave the semaphore
  as the single, explicit knob.
- **Structured concurrency** (`StructuredTaskScope`) — cleanest lifecycle and
  cancellation, but a *preview* API in Java 21 (needs `--enable-preview`). Deferred
  until it stabilises; the virtual-thread executor's try-with-resources `close()`
  already gives us "await all tasks" without preview flags.

## Out of scope (explicitly): per-host concurrency caps

Only the `latest`/`github-release` legs hit GitHub. `current` legs hit our own
deployment endpoints, and `oci-registry` / `http-regex` latest legs hit other hosts
with their own limits. A perfect design would bound *per host*. We chose a single
global cap as the pragmatic safety valve for now: it is trivially retunable once the
baseline reports real numbers, and per-host bounding can be revisited if a single
host proves to be the constraint.
