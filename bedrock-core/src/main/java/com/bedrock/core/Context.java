package com.bedrock.core;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
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
 * Methods like ok(), notFound() do NOT write to the network immediately.
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

    // --- Response Buffer Mutators ---
    
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
                responseContent = toJson(responseBody);
            }
        }
        
        byte[] bytes = responseContent.getBytes(StandardCharsets.UTF_8);
        
        // Apply custom headers injected by Handlers or AfterMiddlewares
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        
        // Write status and body
        exchange.sendResponseHeaders(statusCode, bytes.length == 0 ? -1 : bytes.length);
        
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
        exchange.close();
    }

    // --- Minimalist JSON Serializer based on Reflection ---
    
    private String toJson(Object obj) {
        if (obj == null) return "null";
        
        if (obj instanceof String str) {
            return "\"" + str.replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        
        if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        }
        
        if (obj instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey()).append("\":").append(toJson(entry.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        
        if (obj instanceof Iterable<?> iter) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : iter) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            return sb.append("]").toString();
        }

        Class<?> clazz = obj.getClass();
        
        // 🚀 Native and optimized support for Java Records
        if (clazz.isRecord()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (RecordComponent component : clazz.getRecordComponents()) {
                if (!first) sb.append(",");
                try {
                    Object val = component.getAccessor().invoke(obj);
                    sb.append("\"").append(component.getName()).append("\":").append(toJson(val));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to serialize Record: " + clazz.getSimpleName(), e);
                }
                first = false;
            }
            return sb.append("}").toString();
        }

        // Support for classic POJOs (regular classes)
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true); // Breaking encapsulation for reading
            if (!first) sb.append(",");
            try {
                Object val = field.get(obj);
                sb.append("\"").append(field.getName()).append("\":").append(toJson(val));
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize POJO: " + clazz.getSimpleName(), e);
            }
            first = false;
        }
        return sb.append("}").toString();
    }
}
