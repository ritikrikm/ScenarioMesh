package io.scenariomesh.controljson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * ScenarioMesh-owned JSON codec whose Jackson implementation is package-relocated at build time.
 * Target repositories can therefore carry any Jackson version without changing control-plane
 * serialization behavior or the network protocol bytes produced by ScenarioMesh.
 */
public final class ControlJsonCodec {
    private static final ObjectMapper MAPPER = createMapper();

    private ControlJsonCodec() {}

    public static String write(Object value) throws Exception {
        return MAPPER.writeValueAsString(Objects.requireNonNull(value, "value"));
    }

    public static byte[] writeBytes(Object value) throws Exception {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T read(byte[] bytes, Class<T> type) throws Exception {
        Objects.requireNonNull(bytes, "bytes");
        return MAPPER.readValue(bytes, Objects.requireNonNull(type, "type"));
    }

    public static <T> T read(String json, Class<T> type) throws Exception {
        return MAPPER.readValue(Objects.requireNonNull(json, "json"), Objects.requireNonNull(type, "type"));
    }

    private static ObjectMapper createMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
