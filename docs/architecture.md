# Architecture

Core depends only on typed ports. Framework adapters discover and execute tests using supported framework APIs; core never parses Gherkin. Workers are JVM processes. A future daemon owns project-scoped pools and transports protocol DTOs rather than Java serialization.

## Invariants
A task is assigned once at a time; workers execute one atomic scenario at a time; resource leases are acquired before assignment and released after terminal result; stale fingerprints must never knowingly execute; infrastructure failures remain distinct from test failures.

## Scheduler
The duration-aware scheduler is a priority queue ordered by estimated/historical duration (LPT). Queue insertion/removal is O(log n). Eligibility probing may inspect blocked tasks, restoring them afterward; resource indexing is a future optimization if profiling justifies it.
