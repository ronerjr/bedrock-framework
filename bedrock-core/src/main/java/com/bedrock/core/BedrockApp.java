package com.bedrock.core;

import com.bedrock.exception.BedrockException;
import com.bedrock.ioc.BedrockContainer;
import com.bedrock.web.BedrockController;
import com.bedrock.web.BedrockGet;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Main entry point and orchestrator of the Bedrock framework.
 */
public class BedrockApp {
    
    private final int port;
    private final Router router;
    private final BedrockContainer container;

    private BedrockApp(int port) {
        this.port = port;
        this.router = new Router();
        this.container = new BedrockContainer();
        
        // Auto-mapping for Developer Experience (DX) routes
        this.router.addRoute("GET", "/bedrock/ui", ctx -> ctx.html(BedrockPlayground.getHtml()));
        this.router.addRoute("GET", "/bedrock/api/routes", ctx -> ctx.ok(this.router.getRegisteredRoutes()));
    }

    /**
     * Creates a new server instance on the specified port.
     */
    public static BedrockApp create(int port) {
        return new BedrockApp(port);
    }

    /**
     * Registers a functional GET route.
     */
    public BedrockApp get(String path, Handler handler) {
        router.addRoute("GET", path, handler);
        return this;
    }

    /**
     * Adds a global middleware that will run BEFORE the route handler.
     */
    public BedrockApp before(Middleware middleware) {
        router.before(middleware);
        return this;
    }

    /**
     * Adds a global middleware that will run AFTER the route handler.
     */
    public BedrockApp after(AfterMiddleware middleware) {
        router.after(middleware);
        return this;
    }

    /**
     * Registers classes (Controllers, Services) in the IoC Container and automatically binds
     * methods annotated with @BedrockGet, transforming them into native routes in the Router.
     */
    public BedrockApp bindControllers(Class<?>... classes) {
        // 1. Register all dependencies in the IoC engine
        container.register(classes);
        
        // 2. Scan Controllers to create routes
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(BedrockController.class)) {
                Object controllerInstance = container.getBean(clazz);
                
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(BedrockGet.class)) {
                        BedrockGet annotation = method.getAnnotation(BedrockGet.class);
                        String path = annotation.value();
                        
                        Handler handler = ctx -> {
                            try {
                                method.setAccessible(true);
                                Object result;
                                
                                // Supports methods that receive the Context or no arguments
                                if (method.getParameterCount() == 1 && method.getParameterTypes()[0].equals(Context.class)) {
                                    result = method.invoke(controllerInstance, ctx);
                                } else if (method.getParameterCount() == 0) {
                                    result = method.invoke(controllerInstance);
                                } else {
                                    throw new BedrockException(
                                        "Invalid controller method '" + method.getName() + "'.",
                                        "In Bedrock, controller methods must receive exactly (Context ctx) or have no arguments at all."
                                    );
                                }
                                
                                // If the method returns something other than void, we return it with 200 OK via JSON
                                if (result != null) {
                                    ctx.ok(result);
                                }
                            } catch (InvocationTargetException e) {
                                BedrockLogger.error("CONTROLLER", "Execution error in " + method.getName() + ": " + e.getCause().getMessage());
                                throw new Exception("Execution error in Controller: " + e.getCause().getMessage(), e.getCause());
                            } catch (Exception e) {
                                throw new BedrockException(
                                    "Internal error routing call via Reflection for method '" + method.getName() + "'.",
                                    "Check if the method is accessible and properly configured.",
                                    e
                                );
                            }
                        };
                        
                        router.addRoute("GET", path, handler);
                    }
                }
            }
        }
        return this;
    }

    /**
     * Starts the native HTTP server coupled with Virtual Threads.
     */
    public void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            
            // PERFORMANCE MAGIC: Natively injected Virtual Threads!
            // Each HTTP request will be handled in its own Virtual Thread,
            // without blocking OS threads.
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            
            server.createContext("/", exchange -> {
                Map<String, String> pathParams = new HashMap<>();
                Context ctx = new Context(exchange, pathParams);
                
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                
                try {
                    // 1. Execute Before Middlewares
                    for (Middleware middleware : router.getBeforeMiddlewares()) {
                        middleware.handle(ctx);
                    }
                    
                    // 2. Execute Handler
                    Handler handler = router.findHandler(method, path, pathParams);
                    if (handler != null) {
                        handler.handle(ctx);
                    } else {
                        ctx.notFound("Oops! Route " + method + " " + path + " not mapped in Bedrock.");
                    }

                    // 3. Execute After Middlewares (Response Filters)
                    for (AfterMiddleware middleware : router.getAfterMiddlewares()) {
                        middleware.handle(ctx);
                    }

                    // 4. Flush buffer to network
                    ctx.flush();
                    
                } catch (Exception e) {
                    BedrockLogger.error("HTTP-SERVER", "Internal request failure: " + e.getMessage());
                    e.printStackTrace();
                    try {
                        ctx.badRequest("An internal error occurred: " + e.getMessage());
                        ctx.flush();
                    } catch (Exception ignore) {}
                }
            });
            
            server.start();
            printBanner();
            
        } catch (IOException e) {
            throw new BedrockException(
                "Could not start Bedrock HTTP server on port " + port,
                "Check if the port " + port + " is already in use by another application.",
                e
            );
        }
    }

    private void printBanner() {
        String banner = """
                 ____           _                _    
                |  _ \\         | |              | |   
                | |_) | ___  __| |_ __ ___   ___| | __
                |  _ < / _ \\/ _` | '__/ _ \\ / __| |/ /
                | |_) |  __/ (_| | | | (_) | (__|   < 
                |____/ \\___|\\__,_|_|  \\___/ \\___|_|\\_\\
                """;
        BedrockLogger.banner(banner);
        BedrockLogger.info("SYSTEM", "🦖 Bedrock Java Framework running at full speed on Virtual Threads!");
        BedrockLogger.info("SYSTEM", "🔗 Playground UI: http://localhost:" + port + "/bedrock/ui");
        System.out.println("---------------------------------------------------------");
    }
}
