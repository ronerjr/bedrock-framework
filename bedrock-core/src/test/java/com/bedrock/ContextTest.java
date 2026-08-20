package com.bedrock;

import com.bedrock.core.Context;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextTest {

    public record TestDto(String title, int count) {
    }

    @Test
    void shouldExtractPathAndQueryParams() throws Exception {
        // Mock HttpExchange
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/users?status=active&sort=desc"));
        when(exchange.getRequestMethod()).thenReturn("GET");

        // Mock Path Params
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", "99");

        Context context = new Context(exchange, pathParams);

        // Assert Path Params
        assertEquals("99", context.pathParam("id"));

        // Assert Query Params
        assertEquals("active", context.queryParam("status"));
        assertEquals("desc", context.queryParam("sort"));
        assertNull(context.queryParam("unknown"));
    }

    @Test
    void shouldBufferResponseCorrectly() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/test"));

        Context context = new Context(exchange, new HashMap<>());

        // Use ok() to buffer response
        context.ok("Hello World");

        assertEquals(200, context.getStatusCode());
        verify(exchange, never()).sendResponseHeaders(anyInt(), anyLong());
    }

    @Test
    void shouldParseRequestBodyAsRecord() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/items"));

        String jsonPayload = "{\"title\":\"Clean Code\",\"count\":5}";
        when(exchange.getRequestBody())
                .thenReturn(new ByteArrayInputStream(jsonPayload.getBytes(StandardCharsets.UTF_8)));

        Context context = new Context(exchange, new HashMap<>());
        TestDto dto = context.bodyAs(TestDto.class);

        assertNotNull(dto);
        assertEquals("Clean Code", dto.title());
        assertEquals(5, dto.count());
    }

    @Test
    void shouldHandleNoContentAndStatusModifiers() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/delete"));

        Context context = new Context(exchange, new HashMap<>());
        context.noContent();

        assertEquals(204, context.getStatusCode());
        assertNull(context.getResponseBody());

        context.status(418);
        assertEquals(418, context.getStatusCode());
    }
}
