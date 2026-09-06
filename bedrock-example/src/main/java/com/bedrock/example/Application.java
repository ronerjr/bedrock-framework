package com.bedrock.example;

import com.bedrock.core.BedrockApp;

public class Application {

    public static void main(String[] args) {
        BedrockApp app = BedrockApp.create(8080);
        
        // 1. Before Middleware: Intercepts before the Handler
        app.before(ctx -> {
            System.out.println("[LOG] \ud83e\udeb5 Request intercepted at: " + ctx.path());
        });
        
        // 2. After Middleware: Intercepts before flushing to network
        app.after(ctx -> {
            ctx.setHeader("X-Powered-By", "Bedrock-Java-21");
            ctx.setHeader("Access-Control-Allow-Origin", "*");
        });

        // 3. Simple programmatic route
        app.get("/api/ping", ctx -> ctx.ok("pong"));

        // 4. Interface Inversion (SOLID 'D'):
        // Decouple controllers from concrete service implementations.
        // Bedrock injects the bound concrete class when any controller declares IUserService.
        app.bind(IUserService.class, UserService.class);

        // 5. Global Exception Handling:
        // Centralized domain exception handling eliminates boilerplate try/catch in controllers.
        app.onError(UserNotFoundException.class, (ctx, ex) -> {
            ctx.notFound(java.util.Map.of(
                "error", ex.getMessage(),
                "status", 404
            ));
        });

        // 6. Explicit Component Registration & Dependency Injection:
        // Pass all components (Services, Controllers) to the engine.
        // Bedrock builds the dependency graph, injects services via constructor,
        // and routes HTTP endpoints automatically!
        app.register(UserService.class, UserController.class)
           .start();
    }
}
