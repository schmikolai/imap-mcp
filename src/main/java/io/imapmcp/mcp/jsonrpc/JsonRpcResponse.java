package io.imapmcp.mcp.jsonrpc;

import com.fasterxml.jackson.annotation.JsonInclude;

public class JsonRpcResponse {

    private final String jsonrpc = "2.0";
    private Object id;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Object result;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private JsonRpcError error;

    public static JsonRpcResponse success(Object id, Object result) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.id = id;
        response.result = result;
        return response;
    }

    public static JsonRpcResponse error(Object id, int code, String message) {
        JsonRpcResponse response = new JsonRpcResponse();
        response.id = id;
        response.error = new JsonRpcError(code, message, null);
        return response;
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public Object getId() {
        return id;
    }

    public Object getResult() {
        return result;
    }

    public JsonRpcError getError() {
        return error;
    }
}
