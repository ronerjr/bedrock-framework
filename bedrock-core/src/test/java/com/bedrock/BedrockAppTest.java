package com.bedrock;

import com.bedrock.core.BedrockApp;
import com.bedrock.core.Context;
import com.bedrock.core.Router;
import com.bedrock.web.*;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BedrockAppTest {

    public record ItemDto(String name, double price) {}

    @BedrockController
    static class SampleController {

        @BedrockGet("/api/items")
        public ItemDto getItem() {
            return new ItemDto("Keyboard", 99.9);
        }

        @BedrockPost("/api/items")
        public ItemDto createItem(ItemDto dto) {
            return dto;
        }

        @BedrockPut("/api/items/{id}")
        public void updateItem(Context ctx) {
            String id = ctx.pathParam("id");
            ItemDto dto = ctx.bodyAs(ItemDto.class);
            ctx.ok(new ItemDto(dto.name() + "-" + id, dto.price()));
        }

        @BedrockDelete("/api/items/{id}")
        public void deleteItem() {
            // void method defaults to 204 No Content
        }
    }

    @Test
    void shouldBindControllerRoutesAndHandleRequests() throws Exception {
        BedrockApp app = BedrockApp.create(9090);
        app.bindControllers(SampleController.class);

        // Access internal router via reflection to test handlers
        Field routerField = BedrockApp.class.getDeclaredField("router");
        routerField.setAccessible(true);
        Router router = (Router) routerField.get(app);

        // 1. Test GET /api/items
        HttpExchange getExchange = mock(HttpExchange.class);
        when(getExchange.getRequestURI()).thenReturn(new URI("/api/items"));
        when(getExchange.getRequestMethod()).thenReturn("GET");
        Context getCtx = new Context(getExchange, new HashMap<>());

        var getHandler = router.findHandler("GET", "/api/items", new HashMap<>());
        assertNotNull(getHandler);
        getHandler.handle(getCtx);
        assertEquals(200, getCtx.getStatusCode());
        assertNotNull(getCtx.getResponseBody());

        // 2. Test POST /api/items with automatic DTO injection
        HttpExchange postExchange = mock(HttpExchange.class);
        when(postExchange.getRequestURI()).thenReturn(new URI("/api/items"));
        when(postExchange.getRequestMethod()).thenReturn("POST");
        String postBody = "{\"name\":\"Mouse\",\"price\":49.5}";
        when(postExchange.getRequestBody()).thenReturn(new ByteArrayInputStream(postBody.getBytes(StandardCharsets.UTF_8)));
        Context postCtx = new Context(postExchange, new HashMap<>());

        var postHandler = router.findHandler("POST", "/api/items", new HashMap<>());
        assertNotNull(postHandler);
        postHandler.handle(postCtx);
        assertEquals(201, postCtx.getStatusCode()); // 201 Created for POST returning object
        assertTrue(postCtx.getResponseBody() instanceof ItemDto item && item.name().equals("Mouse"));

        // 3. Test DELETE /api/items/{id} returning 204 No Content
        HttpExchange deleteExchange = mock(HttpExchange.class);
        when(deleteExchange.getRequestURI()).thenReturn(new URI("/api/items/42"));
        when(deleteExchange.getRequestMethod()).thenReturn("DELETE");
        Map<String, String> deleteParams = new HashMap<>();
        Context deleteCtx = new Context(deleteExchange, deleteParams);

        var deleteHandler = router.findHandler("DELETE", "/api/items/42", deleteParams);
        assertNotNull(deleteHandler);
        deleteHandler.handle(deleteCtx);
        assertEquals(204, deleteCtx.getStatusCode());
        assertNull(deleteCtx.getResponseBody());
    }
}
