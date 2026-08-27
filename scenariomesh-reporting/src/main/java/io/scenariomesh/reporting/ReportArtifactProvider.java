package io.scenariomesh.reporting;

import java.util.List;

/** SPI for integrations that expose references to screenshots, logs, traces, or external reports. */
public interface ReportArtifactProvider {
    String id();

    List<ReportArtifact> artifacts(ReportExportContext context) throws Exception;
}
