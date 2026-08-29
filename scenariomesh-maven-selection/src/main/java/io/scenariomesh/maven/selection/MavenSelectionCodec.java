package io.scenariomesh.maven.selection;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/** Value-safe transport for Maven selector collections across ScenarioMesh process boundaries. */
public final class MavenSelectionCodec {
    private MavenSelectionCodec() {}

    public static String encode(Collection<String> values) {
        if (values == null || values.isEmpty()) return "";
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return values.stream()
                .map(value -> encoder.encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .reduce((left, right) -> left + "." + right)
                .orElse("");
    }

    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        Base64.Decoder decoder = Base64.getUrlDecoder();
        List<String> values = new ArrayList<>();
        for (String token : encoded.split("\\.", -1)) {
            if (token.isEmpty()) throw new IllegalArgumentException("Invalid empty Maven selector transport token");
            values.add(new String(decoder.decode(token), StandardCharsets.UTF_8));
        }
        return List.copyOf(values);
    }
}
