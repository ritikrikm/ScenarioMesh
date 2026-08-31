package io.scenariomesh.maven.extension;

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Detects Surefire's per-fork placeholder before Maven property interpolation can hide it.
 *
 * <p>Surefire substitutes this value from its actual fork/Maven-thread topology in argLine,
 * environment variables, and system properties. ScenarioMesh workers intentionally do not
 * reproduce that topology, so transparent takeover must leave the executor intact.</p>
 */
final class MavenForkNumberCompatibility {
    private static final List<String> TOKENS = List.of("${surefire.forkNumber}", "@{surefire.forkNumber}");

    Analysis analyze(Plugin plugin, Collection<String> executionIds) {
        if (plugin == null) return Analysis.compatible();
        List<String> locations = new ArrayList<>();
        inspect(plugin.getConfiguration(), "maven executor plugin configuration", locations);
        if (plugin.getExecutions() != null) {
            for (PluginExecution execution : plugin.getExecutions()) {
                if (executionIds != null && !executionIds.contains(execution.getId())) continue;
                inspect(execution.getConfiguration(), "maven executor execution '" + execution.getId() + "'", locations);
            }
        }
        return locations.isEmpty()
                ? Analysis.compatible()
                : Analysis.unsupported("Surefire ${surefire.forkNumber} is substituted from native fork and Maven-thread "
                        + "topology, which ScenarioMesh workers do not reproduce; found in " + String.join(", ", locations));
    }

    private void inspect(Object raw, String location, List<String> locations) {
        if (!(raw instanceof Xpp3Dom node)) return;
        inspectNode(node, location, "", locations);
    }

    private void inspectNode(Xpp3Dom node, String location, String path, List<String> locations) {
        String currentPath = path.isEmpty() ? node.getName() : path + "/" + node.getName();
        String value = node.getValue();
        if (value != null && containsForkNumber(value)) locations.add(location + " <" + currentPath + ">");
        for (Xpp3Dom child : node.getChildren()) inspectNode(child, location, currentPath, locations);
    }

    private boolean containsForkNumber(String value) {
        return TOKENS.stream().anyMatch(value::contains);
    }

    record Analysis(boolean supported, String reason) {
        static Analysis compatible() { return new Analysis(true, null); }
        static Analysis unsupported(String reason) { return new Analysis(false, reason); }
    }
}
