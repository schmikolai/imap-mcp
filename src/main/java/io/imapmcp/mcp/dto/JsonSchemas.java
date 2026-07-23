package io.imapmcp.mcp.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON Schema builders — just enough to describe MCP tool inputs. */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    public static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    public static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    public static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }
}
