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

        // 4. Auto-discovery of Controllers & Start Engine
        app.bindControllers(UserService.class, UserController.class)
           .start();
    }
}
