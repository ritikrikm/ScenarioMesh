package io.scenariomesh.reporting;

/**
 * SPI for optional downstream reporting integrations (for example, enterprise dashboards
 * or vendor-specific formats). Installing a provider is an explicit request to export.
 */
public interface ReportExporter {
    /** Stable human/machine identifier used in diagnostics. */
    String id();

    /** Export the completed run. Implementations must not mutate ScenarioMesh built-in reports. */
    void export(ReportExportContext context) throws Exception;
}
