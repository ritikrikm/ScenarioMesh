package io.scenariomesh.coordinator.distributed;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable capabilities advertised by a remotely hosted ScenarioMesh worker process. */
public record RemoteWorkerRegistration(
        String workerId,
        String runtimeFingerprint,
        int slots,
        int javaFeature,
        String osName,
        String osArch,
        Set<String> adapterIds,
        Set<String> engineIds,
        Map<String, String> labels) {

    public RemoteWorkerRegistration {
        workerId = require(workerId, "workerId");
        runtimeFingerprint = require(runtimeFingerprint, "runtimeFingerprint");
        if (slots < 1) throw new IllegalArgumentException("slots must be greater than zero");
        if (javaFeature < 17) throw new IllegalArgumentException("remote worker Java must be 17 or newer");
        osName = require(osName, "osName");
        osArch = require(osArch, "osArch");
        adapterIds = Set.copyOf(adapterIds == null ? Set.of() : adapterIds);
        engineIds = Set.copyOf(engineIds == null ? Set.of() : engineIds);
        labels = Map.copyOf(labels == null ? Map.of() : labels);
    }

    public boolean canRun(String requiredFingerprint, String adapterId, String engineId) {
        if (!runtimeFingerprint.equals(requiredFingerprint)) return false;
        if (adapterId != null && !adapterId.isBlank() && !adapterIds.contains(adapterId)) return false;
        return engineId == null || engineId.isBlank() || engineIds.contains(engineId);
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
