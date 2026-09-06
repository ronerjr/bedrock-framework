package com.bedrock;

import com.bedrock.core.Context;
import com.bedrock.exception.BedrockValidationException;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextValidationTest {

    @Test
    void shouldExtractValidParamAsInt() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/users/42"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", "42");

        Context ctx = new Context(exchange, pathParams);
        int id = ctx.paramAsInt("id");

        assertEquals(42, id);
    }

    @Test
    void shouldThrowWhenParamAsIntIsMissing() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/users"));

        Context ctx = new Context(exchange, new HashMap<>());

        BedrockValidationException ex = assertThrows(BedrockValidationException.class, () -> {
            ctx.paramAsInt("id");
        });

        assertEquals("id", ex.getParameterName());
        assertNull(ex.getInvalidValue());
        assertTrue(ex.getReason().contains("Missing required path parameter 'id'"));
        assertTrue(ex.getAction().contains("Ensure the route defines {id}"));
    }

    @Test
    void shouldThrowWhenParamAsIntIsNotNumeric() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/users/abc"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", "abc");

        Context ctx = new Context(exchange, pathParams);

        BedrockValidationException ex = assertThrows(BedrockValidationException.class, () -> {
            ctx.paramAsInt("id");
        });

        assertEquals("id", ex.getParameterName());
        assertEquals("abc", ex.getInvalidValue());
        assertTrue(ex.getReason().contains("'abc' is not a valid integer"));
    }

    @Test
    void shouldExtractValidParamAsLong() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/accounts/9876543210"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("accountId", "9876543210");

        Context ctx = new Context(exchange, pathParams);
        long accountId = ctx.paramAsLong("accountId");

        assertEquals(9876543210L, accountId);
    }

    @Test
    void shouldThrowWhenParamAsLongIsNotNumeric() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/accounts/invalid"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("accountId", "invalid");

        Context ctx = new Context(exchange, pathParams);

        BedrockValidationException ex = assertThrows(BedrockValidationException.class, () -> {
            ctx.paramAsLong("accountId");
        });

        assertEquals("accountId", ex.getParameterName());
        assertEquals("invalid", ex.getInvalidValue());
        assertTrue(ex.getReason().contains("not a valid long number"));
    }

    @Test
    void shouldUrlDecodeQueryParams() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/search?query=Bedrock+Framework&city=S%C3%A3o+Paulo"));

        Context ctx = new Context(exchange, new HashMap<>());

        assertEquals("Bedrock Framework", ctx.queryParam("query"));
        assertEquals("São Paulo", ctx.queryParam("city"));
    }

    @Test
    void shouldExtractValidQueryParamAsInt() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/products?page=3&limit=25"));

        Context ctx = new Context(exchange, new HashMap<>());

        assertEquals(3, ctx.queryParamAsInt("page"));
        assertEquals(25, ctx.queryParamAsInt("limit"));
    }

    @Test
    void shouldHandleMissingAndInvalidQueryParamAsInt() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/products?page=notanumber"));

        Context ctx = new Context(exchange, new HashMap<>());

        // Missing query parameter returns null for Integer or fallback value for primitive overload
        assertNull(ctx.queryParamAsInt("missing"));
        assertEquals(1, ctx.queryParamAsInt("missing", 1));

        // Invalid query parameter throws BedrockValidationException
        BedrockValidationException ex = assertThrows(BedrockValidationException.class, () -> {
            ctx.queryParamAsInt("page");
        });
        assertEquals("page", ex.getParameterName());
        assertEquals("notanumber", ex.getInvalidValue());
        assertTrue(ex.getReason().contains("not a valid integer"));
    }

    @Test
    void shouldSupportCustomObjectsInBadRequestAndNotFound() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));

        Context ctx = new Context(exchange, new HashMap<>());

        Map<String, Object> errorPayload = Map.of("code", "ITEM_NOT_FOUND", "id", 123);
        ctx.notFound(errorPayload);

        assertEquals(404, ctx.getStatusCode());
        assertEquals(errorPayload, ctx.getResponseBody());

        Map<String, Object> badPayload = Map.of("code", "INVALID_INPUT");
        ctx.badRequest(badPayload);

        assertEquals(400, ctx.getStatusCode());
        assertEquals(badPayload, ctx.getResponseBody());
    }
}
