package io.imapmcp.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single JSON-RPC 2.0 message. {@code id} is {@code null} for
 * notifications (e.g. {@code notifications/initialized}) and non-null for
 * requests that expect a response — that distinction drives dispatch in
 * {@link io.imapmcp.mcp.McpController}. Batched arrays (once part of the MCP
 * spec) are not supported here; the current spec revision this targets
 * dropped batching.
 */
public class JsonRpcRequest {

    private String jsonrpc = "2.0";
    private Object id;
    private String method;
    private JsonNode params;

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public JsonNode getParams() {
        return params;
    }

    public void setParams(JsonNode params) {
        this.params = params;
    }
}
