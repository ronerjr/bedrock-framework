package com.bedrock;

import com.bedrock.core.Context;
import com.bedrock.core.Handler;
import com.bedrock.core.RouteDefinition;
import com.bedrock.core.Router;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router();
    }

    @Test
    void shouldRegisterAndFindStaticRoute() {
        Handler mockHandler = ctx -> {};
        router.addRoute("GET", "/api/ping", mockHandler);

        Map<String, String> params = new HashMap<>();
        Handler found = router.findHandler("GET", "/api/ping", params);

        assertNotNull(found);
        assertEquals(mockHandler, found);
        assertTrue(params.isEmpty());
    }

    @Test
    void shouldRegisterAndFindDynamicRouteWithVariables() {
        Handler mockHandler = ctx -> {};
        router.addRoute("GET", "/api/users/{id}/details", mockHandler);

        Map<String, String> params = new HashMap<>();
        Handler found = router.findHandler("GET", "/api/users/123/details", params);

        assertNotNull(found);
        assertEquals(mockHandler, found);
        assertEquals("123", params.get("id"));
    }

    @Test
    void shouldHandleMultiVerbRoutesCorrectly() {
        Handler getHandler = ctx -> {};
        Handler postHandler = ctx -> {};
        Handler putHandler = ctx -> {};
        Handler deleteHandler = ctx -> {};

        router.addRoute("GET", "/api/items", getHandler);
        router.addRoute("POST", "/api/items", postHandler);
        router.addRoute("PUT", "/api/items/{id}", putHandler);
        router.addRoute("DELETE", "/api/items/{id}", deleteHandler);

        Map<String, String> params = new HashMap<>();

        assertEquals(getHandler, router.findHandler("GET", "/api/items", params));
        assertEquals(postHandler, router.findHandler("POST", "/api/items", params));
        assertEquals(putHandler, router.findHandler("PUT", "/api/items/42", params));
        assertEquals("42", params.get("id"));

        params.clear();
        assertEquals(deleteHandler, router.findHandler("DELETE", "/api/items/99", params));
        assertEquals("99", params.get("id"));
    }

    @Test
    void shouldReturnNullForUnmappedRoute() {
        Handler mockHandler = ctx -> {};
        router.addRoute("GET", "/api/users", mockHandler);

        Map<String, String> params = new HashMap<>();
        Handler found = router.findHandler("POST", "/api/users", params); // Wrong method

        assertNull(found);
        assertTrue(params.isEmpty());
    }

    @Test
    void shouldRegisterMiddlewares() {
        router.before(ctx -> {});
        router.after(ctx -> {});

        assertEquals(1, router.getBeforeMiddlewares().size());
        assertEquals(1, router.getAfterMiddlewares().size());
    }

    @Test
    void shouldReturnRegisteredRoutes() {
        router.addRoute("GET", "/route1", ctx -> {});
        router.addRoute("POST", "/route2", ctx -> {});

        List<RouteDefinition> routes = router.getRegisteredRoutes();
        assertEquals(2, routes.size());
    }
}
