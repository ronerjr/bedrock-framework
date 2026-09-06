package com.bedrock;

import com.bedrock.core.BedrockApp;
import com.bedrock.core.Context;
import com.bedrock.exception.BedrockValidationException;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    static class BaseDomainException extends RuntimeException {
        public BaseDomainException(String message) {
            super(message);
        }
    }

    static class ResourceNotFoundException extends BaseDomainException {
        public ResourceNotFoundException(String message) {
            super(message);
        }
    }

    @Test
    void shouldHandleRegisteredExceptionCustomHandler() throws Exception {
        BedrockApp app = BedrockApp.create(8080);
        app.onError(ResourceNotFoundException.class, (ctx, ex) -> {
            ctx.status(404).json(Map.of("error", ex.getMessage(), "code", "NOT_FOUND"));
        });

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));
        Context ctx = new Context(exchange, new HashMap<>());

        app.handleException(ctx, new ResourceNotFoundException("Item 42 not found"));

        assertEquals(404, ctx.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.getResponseBody();
        assertNotNull(body);
        assertEquals("Item 42 not found", body.get("error"));
        assertEquals("NOT_FOUND", body.get("code"));
    }

    @Test
    void shouldHandleExceptionPolymorphicallyBySuperclass() throws Exception {
        BedrockApp app = BedrockApp.create(8080);
        app.onError(BaseDomainException.class, (ctx, ex) -> {
            ctx.status(422).json(Map.of("domainError", ex.getMessage()));
        });

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));
        Context ctx = new Context(exchange, new HashMap<>());

        // ResourceNotFoundException extends BaseDomainException
        app.handleException(ctx, new ResourceNotFoundException("Domain rule violated"));

        assertEquals(422, ctx.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.getResponseBody();
        assertNotNull(body);
        assertEquals("Domain rule violated", body.get("domainError"));
    }

    @Test
    void shouldUnwrapInvocationTargetException() throws Exception {
        BedrockApp app = BedrockApp.create(8080);
        app.onError(ResourceNotFoundException.class, (ctx, ex) -> {
            ctx.status(404).json(Map.of("unwrapped", true, "detail", ex.getMessage()));
        });

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));
        Context ctx = new Context(exchange, new HashMap<>());

        InvocationTargetException ite = new InvocationTargetException(new ResourceNotFoundException("Wrapped resource not found"));
        app.handleException(ctx, ite);

        assertEquals(404, ctx.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.getResponseBody();
        assertNotNull(body);
        assertEquals(true, body.get("unwrapped"));
        assertEquals("Wrapped resource not found", body.get("detail"));
    }

    @Test
    void shouldAutomaticallyHandleBedrockValidationExceptionWithProblemDetails400() throws Exception {
        BedrockApp app = BedrockApp.create(8080);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));
        Context ctx = new Context(exchange, new HashMap<>());

        BedrockValidationException bve = new BedrockValidationException(
                "userId",
                "abc",
                "Parameter 'userId' must be an integer",
                "Send an integer like /users/123"
        );

        app.handleException(ctx, bve);

        assertEquals(400, ctx.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.getResponseBody();
        assertNotNull(body);
        assertEquals("https://bedrock.dev/errors/validation-error", body.get("type"));
        assertEquals("Validation Error", body.get("title"));
        assertEquals(400, body.get("status"));
        assertEquals("userId", body.get("parameter"));
        assertEquals("abc", body.get("invalidValue"));
        assertEquals("Send an integer like /users/123", body.get("action"));
    }

    @Test
    void shouldFallbackToRfc7807ProblemDetails500ForUnhandledExceptions() throws Exception {
        BedrockApp app = BedrockApp.create(8080);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));
        Context ctx = new Context(exchange, new HashMap<>());

        app.handleException(ctx, new NullPointerException("Simulated unexpected null pointer"));

        assertEquals(500, ctx.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.getResponseBody();
        assertNotNull(body);
        assertEquals("https://bedrock.dev/errors/internal-server-error", body.get("type"));
        assertEquals("Internal Server Error", body.get("title"));
        assertEquals(500, body.get("status"));
        assertEquals("Simulated unexpected null pointer", body.get("detail"));
    }
}
