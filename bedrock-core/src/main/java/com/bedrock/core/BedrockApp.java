package com.bedrock.core;

import com.bedrock.exception.BedrockException;
import com.bedrock.exception.BedrockValidationException;
import com.bedrock.ioc.BedrockContainer;
import com.bedrock.web.*;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Main entry point and orchestrator of the Bedrock framework.
 */
public class BedrockApp {
    
    private final int port;
    private final Router router;
    private final BedrockContainer container;
    private final Map<Class<? extends Throwable>, ErrorHandler<? extends Throwable>> errorHandlers = new LinkedHashMap<>();

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
     * Registers a functional POST route.
     */
    public BedrockApp post(String path, Handler handler) {
        router.addRoute("POST", path, handler);
        return this;
    }

    /**
     * Registers a functional PUT route.
     */
    public BedrockApp put(String path, Handler handler) {
        router.addRoute("PUT", path, handler);
        return this;
    }

    /**
     * Registers a functional DELETE route.
     */
    public BedrockApp delete(String path, Handler handler) {
        router.addRoute("DELETE", path, handler);
        return this;
    }

    /**
     * Registers a functional PATCH route.
     */
    public BedrockApp patch(String path, Handler handler) {
        router.addRoute("PATCH", path, handler);
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
     * 🎓 BEDROCK TUTORIAL: Interface Inversion (SOLID 'D')
     * 
     * Binds an interface or abstract class to a concrete implementation in the IoC Container.
     * When any component declares the interface as a constructor dependency,
     * Bedrock resolves and injects an instance of the concrete class.
     * 
     * Example:
     *   app.bind(IUserService.class, UserService.class);
     */
    public <T> BedrockApp bind(Class<T> interfaceClass, Class<? extends T> implementationClass) {
        container.bind(interfaceClass, implementationClass);
        return this;
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Pre-configured Bean Registration
     * 
     * Registers an existing pre-configured instance (such as a BedrockJdbc, DataSource,
     * or third-party client) in the IoC container.
     * Any registered controller or service requiring this type will have this instance injected.
     */
    public <T> BedrockApp registerInstance(Class<T> type, T instance) {
        container.registerInstance(type, instance);
        return this;
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Global Exception Handling
     * 
     * Registers a custom handler for a specific exception type (or any subclass).
     * If an uncaught exception of this type occurs during route execution,
     * this handler is invoked to populate the response buffer before sending it to the client.
     * 
     * Example:
     *   app.onError(UserNotFoundException.class, (ctx, ex) -> {
     *       ctx.notFound(Map.of("error", ex.getMessage()));
     *   });
     */
    @SuppressWarnings("unchecked")
    public <E extends Throwable> BedrockApp onError(Class<E> exceptionClass, ErrorHandler<E> handler) {
        errorHandlers.put(exceptionClass, handler);
        return this;
    }

    /**
     * Returns the underlying IoC Container instance.
     */
    public BedrockContainer getContainer() {
        return container;
    }

    /**
     * Internal exception dispatcher. Invokes registered custom error handlers,
     * standard validation handlers, or RFC 7807 fallback.
     */
    @SuppressWarnings("unchecked")
    public void handleException(Context ctx, Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof InvocationTargetException ite && ite.getCause() != null) {
            cause = ite.getCause();
        }

        // 1. Check user-registered exception handlers
        for (Map.Entry<Class<? extends Throwable>, ErrorHandler<? extends Throwable>> entry : errorHandlers.entrySet()) {
            if (entry.getKey().isAssignableFrom(cause.getClass())) {
                try {
                    ErrorHandler<Throwable> handler = (ErrorHandler<Throwable>) entry.getValue();
                    handler.handle(ctx, cause);
                    return;
                } catch (Exception handlerEx) {
                    BedrockLogger.error("EXCEPTION-HANDLER", "Error executing custom error handler for " + cause.getClass().getSimpleName() + ": " + handlerEx.getMessage());
                    cause = handlerEx;
                    break;
                }
            }
        }

        // 2. Default BedrockValidationException handler -> 400 Bad Request
        if (cause instanceof BedrockValidationException bve) {
            Map<String, Object> problem = new LinkedHashMap<>();
            problem.put("type", "https://bedrock.dev/errors/validation-error");
            problem.put("title", "Validation Error");
            problem.put("status", 400);
            problem.put("detail", bve.getMessage());
            problem.put("parameter", bve.getParameterName());
            if (bve.getInvalidValue() != null) {
                problem.put("invalidValue", bve.getInvalidValue());
            }
            if (bve.getAction() != null) {
                problem.put("action", bve.getAction());
            }
            ctx.status(400).json(problem);
            return;
        }

        // 3. Fallback: RFC 7807 Problem Details 500 Internal Server Error
        BedrockLogger.error("HTTP-SERVER", "Unhandled exception in request: " + cause.getMessage());
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://bedrock.dev/errors/internal-server-error");
        problem.put("title", "Internal Server Error");
        problem.put("status", 500);
        problem.put("detail", cause.getMessage() != null ? cause.getMessage() : "An unexpected error occurred");
        ctx.status(500).json(problem);
    }

    /**
     * 🎓 BEDROCK TUTORIAL: Explicit Component Registration
     * 
     * Registers application classes (Services, Repositories, Controllers) in the IoC Container.
     * Bedrock resolves constructor dependencies in topological order and maps any
     * @BedrockController methods to HTTP routes.
     */
    public BedrockApp register(Class<?>... classes) {
        // 1. Register all dependencies in the IoC engine
        container.register(classes);
        
        // 2. Scan Controllers to create routes
        for (Class<?> clazz : classes) {
            if (clazz.isAnnotationPresent(BedrockController.class)) {
                Object controllerInstance = container.getBean(clazz);
                
                for (Method method : clazz.getDeclaredMethods()) {
                    RouteInfo routeInfo = extractRouteInfo(method);
                    if (routeInfo == null) {
                        continue;
                    }

                    String httpMethod = routeInfo.httpMethod();
                    String path = routeInfo.path();
                    
                    Handler handler = ctx -> {
                        try {
                            method.setAccessible(true);
                            Parameter[] parameters = method.getParameters();
                            Object[] args = new Object[parameters.length];

                            for (int i = 0; i < parameters.length; i++) {
                                Class<?> paramType = parameters[i].getType();
                                if (paramType.equals(Context.class)) {
                                    args[i] = ctx;
                                } else {
                                    // Auto-deserialization of Request Body to DTO
                                    String body = ctx.body();
                                    if (body == null || body.trim().isEmpty()) {
                                        args[i] = null;
                                    } else {
                                        args[i] = BedrockJson.fromJson(body, paramType);
                                    }
                                }
                            }

                            Object result = method.invoke(controllerInstance, args);
                            
                            // Response convention handling
                            if (result != null) {
                                if (ctx.getResponseBody() == null) {
                                    if ("POST".equalsIgnoreCase(httpMethod)) {
                                        ctx.created(result);
                                    } else {
                                        ctx.ok(result);
                                    }
                                }
                            } else if (method.getReturnType().equals(void.class) && ctx.getResponseBody() == null) {
                                if ("DELETE".equalsIgnoreCase(httpMethod) || ctx.getStatusCode() == 200) {
                                    ctx.noContent();
                                }
                            }
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause() != null ? e.getCause() : e;
                            if (cause instanceof Exception ex) {
                                throw ex;
                            }
                            throw new RuntimeException(cause);
                        }
                    };

                    router.addRoute(httpMethod, path, handler);
                }
            }
        }
        return this;
    }

    /**
     * Alias for {@link #register(Class[])}.
     */
    public BedrockApp bindControllers(Class<?>... classes) {
        return register(classes);
    }

    private record RouteInfo(String httpMethod, String path) {}

    private RouteInfo extractRouteInfo(Method method) {
        if (method.isAnnotationPresent(BedrockGet.class)) {
            return new RouteInfo("GET", method.getAnnotation(BedrockGet.class).value());
        }
        if (method.isAnnotationPresent(BedrockPost.class)) {
            return new RouteInfo("POST", method.getAnnotation(BedrockPost.class).value());
        }
        if (method.isAnnotationPresent(BedrockPut.class)) {
            return new RouteInfo("PUT", method.getAnnotation(BedrockPut.class).value());
        }
        if (method.isAnnotationPresent(BedrockDelete.class)) {
            return new RouteInfo("DELETE", method.getAnnotation(BedrockDelete.class).value());
        }
        if (method.isAnnotationPresent(BedrockPatch.class)) {
            return new RouteInfo("PATCH", method.getAnnotation(BedrockPatch.class).value());
        }
        return null;
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
                    
                } catch (Throwable e) {
                    try {
                        handleException(ctx, e);

                        // Execute After Middlewares even on error (e.g. security headers, logging)
                        for (AfterMiddleware middleware : router.getAfterMiddlewares()) {
                            try {
                                middleware.handle(ctx);
                            } catch (Exception ignore) {}
                        }

                        ctx.flush();
                    } catch (Exception ex) {
                        BedrockLogger.error("HTTP-SERVER", "Fatal error flushing response: " + ex.getMessage());
                    }
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
