package io.imapmcp.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.imapmcp.mcp.dto.ToolCallResult;
import io.imapmcp.mcp.jsonrpc.JsonRpcError;
import io.imapmcp.mcp.jsonrpc.JsonRpcRequest;
import io.imapmcp.mcp.jsonrpc.JsonRpcResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The Streamable HTTP MCP transport, scoped to what this phase needs:
 * {@code initialize}, {@code notifications/initialized}, {@code ping},
 * {@code tools/list}, {@code tools/call}. Deliberately out of scope for now
 * (walking-skeleton phase — see plan phase 3): JSON-RPC batching (dropped
 * from the current MCP spec revision anyway), server-initiated SSE streams
 * (GET returns 405), and multi-instance session sharing (sessions are
 * in-process only — see {@link McpSessionRegistry}).
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpSessionRegistry sessions;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;
    private final ObjectMapper objectMapper;

    public McpController(McpSessionRegistry sessions, ToolRegistry toolRegistry,
                          ToolDispatcher toolDispatcher, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.toolRegistry = toolRegistry;
        this.toolDispatcher = toolDispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<Object> handle(@RequestBody JsonRpcRequest request,
                                          @RequestHeader(value = SESSION_HEADER, required = false) String sessionId,
                                          @AuthenticationPrincipal McpPrincipal principal) {
        String method = request.getMethod();
        boolean isNotification = request.getId() == null;

        if (method == null) {
            return isNotification
                    ? ResponseEntity.status(HttpStatus.ACCEPTED).build()
                    : ok(JsonRpcResponse.error(null, JsonRpcError.INVALID_REQUEST, "Missing method"));
        }

        if (isNotification) {
            if ("notifications/initialized".equals(method) && sessionId != null) {
                sessions.markInitialized(sessionId);
            }
            return ResponseEntity.status(HttpStatus.ACCEPTED).build();
        }

        return switch (method) {
            case "initialize" -> handleInitialize(request);
            case "ping" -> ok(JsonRpcResponse.success(request.getId(), Map.of()));
            case "tools/list" -> withInitializedSession(sessionId, request,
                    () -> JsonRpcResponse.success(request.getId(), Map.of("tools", toolRegistry.list())));
            case "tools/call" -> withInitializedSession(sessionId, request, () -> handleToolsCall(request, principal));
            default -> ok(JsonRpcResponse.error(request.getId(), JsonRpcError.METHOD_NOT_FOUND, "Unknown method: " + method));
        };
    }

    @GetMapping
    public ResponseEntity<Void> serverInitiatedStreamNotSupported() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping
    public ResponseEntity<Void> endSession(@RequestHeader(value = SESSION_HEADER, required = false) String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Object> handleInitialize(JsonRpcRequest request) {
        McpSessionRegistry.Session session = sessions.create();
        Map<String, Object> result = Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "imap-mcp", "version", "0.1.0"));
        return ResponseEntity.ok()
                .header(SESSION_HEADER, session.id())
                .body(JsonRpcResponse.success(request.getId(), result));
    }

    private ResponseEntity<Object> withInitializedSession(String sessionId, JsonRpcRequest request,
                                                           Supplier<JsonRpcResponse> action) {
        if (!sessions.isInitialized(sessionId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_REQUEST,
                            "Missing or uninitialized " + SESSION_HEADER));
        }
        return ok(action.get());
    }

    private JsonRpcResponse handleToolsCall(JsonRpcRequest request, McpPrincipal principal) {
        JsonNode params = request.getParams();
        if (params == null || !params.hasNonNull("name")) {
            return JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Missing 'name'");
        }
        String toolName = params.get("name").asText();
        if (toolRegistry.find(toolName).isEmpty()) {
            return JsonRpcResponse.error(request.getId(), JsonRpcError.INVALID_PARAMS, "Unknown tool: " + toolName);
        }

        Map<String, Object> arguments = params.has("arguments") && !params.get("arguments").isNull()
                ? objectMapper.convertValue(params.get("arguments"), new TypeReference<Map<String, Object>>() {
                })
                : Map.of();

        ToolCallResult result = toolDispatcher.call(toolName, arguments, principal);
        return JsonRpcResponse.success(request.getId(), result);
    }

    private ResponseEntity<Object> ok(JsonRpcResponse response) {
        return ResponseEntity.ok(response);
    }
}
