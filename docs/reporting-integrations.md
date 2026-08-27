# Reporting integrations

ScenarioMesh always produces its built-in framework-neutral reports for an owned run. External integrations can extend reporting without changing test execution through Java ServiceLoader SPIs.

## Report exporters

Implement `io.scenariomesh.reporting.ReportExporter` to consume a completed `ReportExportContext` after built-in reports are written. Exporter IDs must be non-blank and unique.

The context exposes the run outcome, reporting directory, built-in report paths, and any collected artifact references.

## Artifact references

Implement `io.scenariomesh.reporting.ReportArtifactProvider` to expose references to screenshots, traces, logs, videos, or external reports produced by a framework/integration.

Providers return `ReportArtifact` values. ScenarioMesh writes a versioned `artifacts.json` manifest and passes the same references to report exporters. ScenarioMesh deliberately does not crawl the workspace or copy arbitrary referenced files.

Local artifact locations must be safe paths relative to the reporting directory. Parent traversal and absolute filesystem paths are rejected. External locations must use HTTPS. Provider IDs and artifact IDs must be unique.

Example conceptual references:

```text
kind=screenshot  location=artifacts/login-failure.png  mediaType=image/png
kind=trace       location=https://observability.example/traces/123  mediaType=text/html
```

This model lets Selenium/browser integrations publish screenshot references and lets observability systems publish trace links without coupling ScenarioMesh core/protocol to any specific framework or storage service.

## JUnit compatibility

ScenarioMesh's built-in JUnit XML/Surefire-style output remains independent of artifact providers. Artifact references are supplemental and do not change Maven success/failure semantics or discovered test identity.
