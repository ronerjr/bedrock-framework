package com.bedrock.example;

import com.bedrock.core.Context;
import com.bedrock.web.*;

import java.util.List;

/**
 * 🎓 BEDROCK TUTORIAL: The Controller Layer
 * 
 * Notice the @BedrockController annotation? It tells the Bedrock Engine:
 * "Hey, I am a web controller! Scan my methods and map them to HTTP routes!"
 */
@BedrockController
public class UserController {

    private final IUserService userService;

    /**
     * 🎓 BEDROCK TUTORIAL: Constructor Injection with Interface Inversion (SOLID 'D')
     * 
     * UserController declares its dependency on the 'IUserService' interface!
     * Bedrock resolves the concrete implementation registered via `app.bind(IUserService.class, UserService.class)`.
     */
    public UserController(IUserService userService) {
        this.userService = userService;
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Return value serialization
     * 
     * When a controller method returns an Object or List, Bedrock automatically
     * serializes it to JSON and sends HTTP 200 OK!
     */
    @BedrockGet("/api/users")
    public List<UserResponse> listUsers() {
        return userService.findAll();
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Typed Path Validation and Domain Exceptions
     * 
     * 1. `ctx.paramAsInt("id")` automatically parses and validates that {id} is an integer.
     *    If the client sends "/api/users/abc", a BedrockValidationException is thrown
     *    and translated into HTTP 400 Bad Request.
     * 2. If the user does not exist, we throw `UserNotFoundException`.
     *    Bedrock's global `app.onError` catches it and formats the HTTP 404 response!
     */
    @BedrockGet("/api/users/{id}")
    public void findUser(Context ctx) {
        int id = ctx.paramAsInt("id");
        UserResponse user = userService.findById(String.valueOf(id));
        
        if (user == null) {
            throw new UserNotFoundException(String.valueOf(id));
        }
        ctx.ok(user);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Automatic DTO Injection and HTTP POST
     * 
     * Notice how this method declares 'CreateUserRequest' directly as a parameter?
     * Bedrock parses the incoming JSON request body and maps it to the Record automatically,
     * responding with HTTP 201 Created by default!
     */
    @BedrockPost("/api/users")
    public UserResponse createUser(CreateUserRequest request) {
        return userService.create(request);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: HTTP PUT with Path Variable and Request Body
     */
    @BedrockPut("/api/users/{id}")
    public void updateUser(Context ctx) {
        int id = ctx.paramAsInt("id");
        UpdateUserRequest request = ctx.bodyAs(UpdateUserRequest.class);
        
        UserResponse updated = userService.update(String.valueOf(id), request);
        if (updated == null) {
            throw new UserNotFoundException(String.valueOf(id));
        }
        ctx.ok(updated);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: HTTP DELETE and Semantic Status Codes
     */
    @BedrockDelete("/api/users/{id}")
    public void deleteUser(Context ctx) {
        int id = ctx.paramAsInt("id");
        boolean deleted = userService.delete(String.valueOf(id));
        
        if (!deleted) {
            throw new UserNotFoundException(String.valueOf(id));
        }
        ctx.noContent();
    }
}
