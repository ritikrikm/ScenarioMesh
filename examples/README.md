# ScenarioMesh examples

This directory contains target repositories used to prove ScenarioMesh behavior against real Maven/test-framework execution models.

These are not special application code that production ScenarioMesh depends on. They are compatibility fixtures and executable documentation.

## What examples are used for

- transparent Maven takeover and native pass-through
- JUnit Platform and Cucumber engine discovery
- Cucumber JUnit 4 behavior
- TestNG behavior
- Surefire/Failsafe selection
- lifecycle and cleanup semantics
- worker/process isolation
- reporting compatibility
- distributed/mixed-version integration gates

## How to read an example

Treat each child directory like an external customer repository. Its `pom.xml`, `.mvn` integration, test sources, resources, and optional `scenariomesh.yml` define the target execution that ScenarioMesh must preserve.

A passing example is evidence only for the semantics that fixture exercises. Production code must never identify one of these paths or artifact names and branch specially for it.

When adding a new compatibility feature, prefer creating or extending a focused example that first demonstrates native Maven behavior, then prove ScenarioMesh produces the same selected tests, lifecycle behavior, and final build semantics without duplicate execution.
