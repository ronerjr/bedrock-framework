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

    private final UserService userService;

    /**
     * 🎓 BEDROCK TUTORIAL: Constructor Injection
     * 
     * Look closely: There is NO @Autowired or @Inject here!
     * Bedrock automatically detects the constructor and knows it needs to inject
     * a 'UserService' for this class to work.
     * 
     * WHY IS THIS BETTER? 
     * 1. Immutability (the field is 'final').
     * 2. Easy to Test (you can just say `new UserController(mockService)` in your JUnit tests).
     */
    public UserController(UserService userService) {
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
     * 🎓 BEDROCK TUTORIAL: Routing and Path Variables
     */
    @BedrockGet("/api/users/{id}")
    public void findUser(Context ctx) {
        String id = ctx.pathParam("id");
        UserResponse user = userService.findById(id);
        
        if (user != null) {
            ctx.ok(user);
        } else {
            ctx.notFound("User not found with id: " + id);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Automatic DTO Injection & HTTP POST
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
        String id = ctx.pathParam("id");
        UpdateUserRequest request = ctx.bodyAs(UpdateUserRequest.class);
        
        UserResponse updated = userService.update(id, request);
        if (updated != null) {
            ctx.ok(updated);
        } else {
            ctx.notFound("User not found with id: " + id);
        }
    }

    /**
     * 🎓 BEDROCK TUTORIAL: HTTP DELETE & 204 No Content
     */
    @BedrockDelete("/api/users/{id}")
    public void deleteUser(Context ctx) {
        String id = ctx.pathParam("id");
        boolean deleted = userService.delete(id);
        
        if (deleted) {
            ctx.noContent();
        } else {
            ctx.notFound("User not found with id: " + id);
        }
    }
}
