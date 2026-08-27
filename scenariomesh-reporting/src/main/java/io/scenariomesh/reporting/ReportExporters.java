package io.scenariomesh.reporting;

import io.scenariomesh.coordinator.RunOutcome;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

/** Loads explicitly installed report exporters through Java's standard SPI. */
public final class ReportExporters {
    private ReportExporters() {}

    public static void export(RunOutcome outcome, Path reportingDirectory,
                              ReportWriter.ReportPaths builtInReports) throws Exception {
        ReportExportContext context = new ReportExportContext(outcome, reportingDirectory, builtInReports);
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (ReportExporter exporter : ServiceLoader.load(
                    ReportExporter.class, Thread.currentThread().getContextClassLoader())) {
                String id = exporter.id();
                if (id == null || id.isBlank()) {
                    throw new IllegalStateException("ScenarioMesh report exporter "
                            + exporter.getClass().getName() + " returned a blank id");
                }
                if (!ids.add(id)) throw new IllegalStateException("Duplicate ScenarioMesh report exporter id '" + id + "'");
                exporter.export(context);
            }
        } catch (ServiceConfigurationError error) {
            throw new IllegalStateException("ScenarioMesh report exporter SPI could not load a provider: "
                    + error.getMessage(), error);
        }
    }
}
