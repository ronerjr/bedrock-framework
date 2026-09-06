package com.bedrock.core;

import com.bedrock.exception.BedrockValidationException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
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
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) return null;
        
        for (String param : query.split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0) {
                String key = URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8);
                if (key.equals(name)) {
                    return URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);
                }
            } else if (eq == -1) {
                String key = URLDecoder.decode(param, StandardCharsets.UTF_8);
                if (key.equals(name)) {
                    return "";
                }
            }
        }
        return null;
    }

    public String queryParam(String name, String defaultValue) {
        String val = queryParam(name);
        return val != null ? val : defaultValue;
    }

    public int queryParamAsInt(String name, int defaultValue) {
        String val = queryParam(name);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new BedrockValidationException(
                name,
                val,
                "Invalid query parameter '" + name + "': '" + val + "' is not a valid integer.",
                "Provide an integer value for query parameter '" + name + "' (e.g. ?" + name + "=10) or omit it to use the default value " + defaultValue + "."
            );
        }
    }

    public Integer queryParamAsInt(String name) {
        String val = queryParam(name);
        if (val == null || val.isBlank()) return null;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new BedrockValidationException(
                name,
                val,
                "Invalid query parameter '" + name + "': '" + val + "' is not a valid integer.",
                "Provide an integer value for query parameter '" + name + "' (e.g. ?" + name + "=10)."
            );
        }
    }

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public int paramAsInt(String name) {
        String val = pathParam(name);
        if (val == null) {
            throw new BedrockValidationException(
                name,
                null,
                "Missing required path parameter '" + name + "'.",
                "Ensure the route defines {" + name + "} and the client provides a value in the URL path."
            );
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new BedrockValidationException(
                name,
                val,
                "Invalid path parameter '" + name + "': '" + val + "' is not a valid integer.",
                "Provide an integer value for '" + name + "' in the URL path (e.g., /123)."
            );
        }
    }

    public long paramAsLong(String name) {
        String val = pathParam(name);
        if (val == null) {
            throw new BedrockValidationException(
                name,
                null,
                "Missing required path parameter '" + name + "'.",
                "Ensure the route defines {" + name + "} and the client provides a value in the URL path."
            );
        }
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            throw new BedrockValidationException(
                name,
                val,
                "Invalid path parameter '" + name + "': '" + val + "' is not a valid long number.",
                "Provide a numeric long value for '" + name + "' in the URL path."
            );
        }
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

    public Context json(Object body) {
        this.responseBody = body;
        this.contentType = "application/json";
        return this;
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
        notFound(Map.of("error", msg));
    }

    public void notFound(Object body) {
        this.statusCode = 404;
        this.responseBody = body;
        this.contentType = "application/json";
    }

    public void badRequest(String msg) {
        badRequest(Map.of("error", msg));
    }

    public void badRequest(Object body) {
        this.statusCode = 400;
        this.responseBody = body;
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
