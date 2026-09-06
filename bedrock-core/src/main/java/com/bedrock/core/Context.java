package com.bedrock.core;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents the context of an HTTP request.
 * 
 * DESIGN PATTERN: Facade
 * This class abstracts the complexity of the native HttpExchange from the JDK,
 * providing expressive methods to read data and send responses.
 * 
 * DESIGN PATTERN: State / Buffer (Front Controller pattern)
 * Methods like ok(), created(), notFound() do NOT write to the network immediately.
 * They merely mutate the internal state (buffering the response intention).
 * This allows AfterMiddlewares to modify headers before the final flush() writes to the stream.
 */
public class Context {
    private final HttpExchange exchange;
    private final Map<String, String> pathParams;
    
    // --- State / Buffer for the HTTP Response ---
    private int statusCode = 200;
    private Object responseBody = null;
    private String contentType = "application/json";
    private final Map<String, String> responseHeaders = new HashMap<>();

    public Context(HttpExchange exchange, Map<String, String> pathParams) {
        this.exchange = exchange;
        this.pathParams = pathParams != null ? pathParams : new HashMap<>();
    }

    // --- Data Reading ---
    
    public String body() {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Error reading request body", e);
        }
    }

    /**
     * Parses the request body JSON into a strongly-typed Java Record or POJO.
     */
    public <T> T bodyAs(Class<T> clazz) {
        return BedrockJson.fromJson(body(), clazz);
    }

    public String queryParam(String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals(name)) {
                return pair[1];
            }
        }
        return null;
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public String path() {
        return exchange.getRequestURI().getPath();
    }

    public String method() {
        return exchange.getRequestMethod();
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Object getResponseBody() {
        return responseBody;
    }

    // --- Response Buffer Mutators ---
    
    /**
     * Sets the HTTP status code explicitly.
     */
    public Context status(int code) {
        this.statusCode = code;
        return this;
    }

    /**
     * Adds a custom header to the HTTP response.
     */
    public void setHeader(String key, String value) {
        this.responseHeaders.put(key, value);
    }

    public void ok(Object body) {
        this.statusCode = 200;
        this.responseBody = body;
        this.contentType = "application/json";
    }

    public void created(Object body) {
        this.statusCode = 201;
        this.responseBody = body;
        this.contentType = "application/json";
    }

    public void noContent() {
        this.statusCode = 204;
        this.responseBody = null;
        this.contentType = "application/json";
    }

    public void notFound(String msg) {
        this.statusCode = 404;
        this.responseBody = Map.of("error", msg);
        this.contentType = "application/json";
    }

    public void badRequest(String msg) {
        this.statusCode = 400;
        this.responseBody = Map.of("error", msg);
        this.contentType = "application/json";
    }

    public void html(String content) {
        this.statusCode = 200;
        this.responseBody = content;
        this.contentType = "text/html";
    }

    /**
     * Flushes the buffered state to the actual network stream.
     * This is exclusively called by the BedrockApp orchestrator at the very end of the pipeline.
     */
    public void flush() throws IOException {
        String responseContent = "";
        
        if (responseBody != null) {
            if ("text/html".equals(contentType)) {
                responseContent = (String) responseBody;
            } else {
                responseContent = BedrockJson.toJson(responseBody);
            }
        }
        
        byte[] bytes = responseContent.getBytes(StandardCharsets.UTF_8);
        
        // Apply custom headers injected by Handlers or AfterMiddlewares
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        
        // 204 No Content should pass -1 for content-length according to RFC
        if (statusCode == 204 || bytes.length == 0) {
            exchange.sendResponseHeaders(statusCode, -1);
        } else {
            exchange.sendResponseHeaders(statusCode, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }
}
