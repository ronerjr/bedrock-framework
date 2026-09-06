package com.bedrock.example;

import com.bedrock.core.BedrockApp;
import com.bedrock.core.Context;
import com.bedrock.exception.BedrockValidationException;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserControllerTest {

    private IUserService userService;
    private UserController userController;

    @BeforeEach
    void setUp() {
        userService = mock(IUserService.class);
        userController = new UserController(userService);
    }

    @Test
    void shouldListAllUsers() {
        List<UserResponse> mockUsers = List.of(
                new UserResponse("1", "Ada Lovelace", "JVM Expert"),
                new UserResponse("2", "Alan Turing", "Algorithm Master")
        );
        when(userService.findAll()).thenReturn(mockUsers);

        List<UserResponse> result = userController.listUsers();

        assertEquals(2, result.size());
        assertEquals("Ada Lovelace", result.get(0).name());
    }

    @Test
    void shouldFindUserByIdWhenUserExists() throws Exception {
        when(userService.findById("1")).thenReturn(new UserResponse("1", "Ada Lovelace", "JVM Expert"));

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/users/1"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", "1");

        Context ctx = new Context(exchange, pathParams);
        userController.findUser(ctx);

        assertEquals(200, ctx.getStatusCode());
        UserResponse response = (UserResponse) ctx.getResponseBody();
        assertNotNull(response);
        assertEquals("Ada Lovelace", response.name());
    }

    @Test
    void shouldThrowUserNotFoundExceptionWhenUserDoesNotExist() throws Exception {
        when(userService.findById("99")).thenReturn(null);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/users/99"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", "99");

        Context ctx = new Context(exchange, pathParams);

        UserNotFoundException ex = assertThrows(UserNotFoundException.class, () -> {
            userController.findUser(ctx);
        });

        assertTrue(ex.getMessage().contains("User with id '99' was not found"));
    }

    @Test
    void shouldThrowValidationExceptionWhenIdIsNotInteger() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/users/invalid-id"));

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", "invalid-id");

        Context ctx = new Context(exchange, pathParams);

        assertThrows(BedrockValidationException.class, () -> {
            userController.findUser(ctx);
        });
    }

    @Test
    void shouldVerifyFullBedrockWiringWithInterfaceBindingAndGlobalErrorHandler() throws Exception {
        IUserRepository mockRepo = mock(IUserRepository.class);
        BedrockApp app = BedrockApp.create(9999)
                .registerInstance(IUserRepository.class, mockRepo)
                .bind(IUserService.class, UserService.class)
                .onError(UserNotFoundException.class, (ctx, ex) -> {
                    ctx.notFound(Map.of("error", ex.getMessage(), "status", 404));
                })
                .register(UserService.class, UserController.class);

        // Verify bean resolution via bound interface
        IUserService resolvedService = app.getContainer().getBean(IUserService.class);
        assertNotNull(resolvedService);
        assertTrue(resolvedService instanceof UserService);

        // Verify controller was injected with the resolved service
        UserController resolvedController = app.getContainer().getBean(UserController.class);
        assertNotNull(resolvedController);

        // Verify global error handler catches UserNotFoundException
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/api/users/999"));
        Context ctx = new Context(exchange, new HashMap<>());

        app.handleException(ctx, new UserNotFoundException("999"));

        assertEquals(404, ctx.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) ctx.getResponseBody();
        assertNotNull(body);
        assertEquals("User with id '999' was not found.", body.get("error"));
    }
}
