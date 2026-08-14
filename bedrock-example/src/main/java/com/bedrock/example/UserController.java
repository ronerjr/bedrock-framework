package com.bedrock.example;

import com.bedrock.core.Context;
import com.bedrock.web.BedrockController;
import com.bedrock.web.BedrockGet;

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
     * 🎓 BEDROCK TUTORIAL: Routing and Path Variables
     * 
     * The @BedrockGet transforms this regular method into an HTTP GET endpoint.
     * The {id} in the path is a Path Variable. You can extract it using `ctx.pathParam("id")`.
     */
    @BedrockGet("/api/users/{id}")
    public void findUser(Context ctx) throws Exception {
        String id = ctx.pathParam("id");
        UserResponse user = userService.findById(id);
        
        if (user != null) {
            // ctx.ok() automatically serializes your Java Record to JSON and sets Status 200!
            ctx.ok(user);
        } else {
            ctx.notFound("User not found with id: " + id);
        }
    }
}
