package io.scenariomesh.adapter.cucumberjunit4;

import org.junit.runner.Description;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Converts a JUnit 4 runner description tree into executable leaf descriptions.
 * The runner itself is a container and is intentionally excluded from the
 * semantic identity. This lets ScenarioMesh reason about generated runner
 * classes without assuming one runner equals one scenario.
 */
final class JUnit4DescriptionLeaves {

    List<Leaf> collect(Description root) {
        Objects.requireNonNull(root, "root");
        List<Leaf> leaves = new ArrayList<>();
        collect(root, new ArrayList<>(), new ArrayList<>(), leaves);
        return List.copyOf(leaves);
    }

    private void collect(Description description,
                         List<Integer> selectorPath,
                         List<String> semanticPath,
                         List<Leaf> leaves) {
        List<Description> children = description.getChildren();
        if (children.isEmpty() && description.isTest()) {
            List<String> identityPath = semanticPath.isEmpty()
                    ? List.of(identityPart(description))
                    : List.copyOf(semanticPath);
            leaves.add(new Leaf(
                    description,
                    List.copyOf(selectorPath),
                    identityPath,
                    String.join(" > ", identityPath)));
            return;
        }

        for (int index = 0; index < children.size(); index++) {
            Description child = children.get(index);
            selectorPath.add(index);
            semanticPath.add(identityPart(child));
            collect(child, selectorPath, semanticPath, leaves);
            semanticPath.remove(semanticPath.size() - 1);
            selectorPath.remove(selectorPath.size() - 1);
        }
    }

    private String identityPart(Description description) {
        String displayName = description.getDisplayName();
        String methodName = description.getMethodName();
        if (methodName == null || methodName.isBlank() || displayName.contains(methodName)) {
            return displayName;
        }
        return displayName + " [" + methodName + "]";
    }

    record Leaf(Description description,
                List<Integer> selectorPath,
                List<String> semanticPath,
                String semanticKey) {
        Leaf {
            Objects.requireNonNull(description, "description");
            selectorPath = List.copyOf(selectorPath);
            semanticPath = List.copyOf(semanticPath);
            Objects.requireNonNull(semanticKey, "semanticKey");
        }
    }
}
