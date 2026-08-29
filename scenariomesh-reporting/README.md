# scenariomesh-reporting

This module converts a completed ScenarioMesh run into durable reports and artifact references.

## Outputs

ScenarioMesh produces framework-neutral run information and compatibility-oriented formats such as JSON summaries, JUnit XML, and Surefire-style XML. Reporting integrations can extend the output without changing execution semantics.

## Flow

```text
validated terminal results for every discovered task
        ↓
aggregate pass/fail/skip/infrastructure state
        ↓
write built-in reports
        ↓
invoke optional reporting/artifact extensions
        ↓
return final build outcome
```

## Correctness boundary

Reporting happens after execution authority has been resolved. It must not invent missing test results or silently drop infrastructure failures.

Large attachments should be represented as controlled artifact references rather than copied into result JSON indiscriminately. Artifact paths must be constrained so a worker cannot use reporting as arbitrary filesystem exfiltration.
