package com.bedrock;

import com.bedrock.core.Context;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextTest {

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
        
        // It shouldn't have called sendResponseHeaders yet because it's buffered!
        verify(exchange, never()).sendResponseHeaders(anyInt(), anyLong());
    }
}
