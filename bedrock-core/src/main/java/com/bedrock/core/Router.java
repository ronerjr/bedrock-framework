package com.bedrock.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.bedrock.core.BedrockLogger;

/**
 * Bedrock Java Routing Engine.
 * 
 * ALGORITHMIC STRATEGY:
 * It stores routes in a ConcurrentHashMap targeting O(1) complexity for exact static path matching.
 * For dynamic routes (e.g. /users/{id}), it gracefully degrades to an O(N) linear search,
 * performing segment-by-segment analysis to extract path variables.
 */
public class Router {
    
    // Key format "METHOD:PATH", e.g. "GET:/users"
    private final ConcurrentHashMap<String, Handler> routes = new ConcurrentHashMap<>();
    
    // Thread-safe lists for middlewares
    private final List<Middleware> beforeMiddlewares = new CopyOnWriteArrayList<>();
    private final List<AfterMiddleware> afterMiddlewares = new CopyOnWriteArrayList<>();

    public void addRoute(String method, String path, Handler handler) {
        String key = method.toUpperCase() + ":" + path;
        routes.put(key, handler);
        BedrockLogger.info("ROUTER", "Route registered: " + method.toUpperCase() + " " + path);
    }

    public void before(Middleware middleware) {
        beforeMiddlewares.add(middleware);
        BedrockLogger.info("ROUTER", "Before Middleware registered");
    }

    public void after(AfterMiddleware middleware) {
        afterMiddlewares.add(middleware);
        BedrockLogger.info("ROUTER", "After Middleware registered");
    }

    public List<RouteDefinition> getRegisteredRoutes() {
        return routes.keySet().stream()
                .map(key -> {
                    String[] parts = key.split(":", 2);
                    return new RouteDefinition(parts[0], parts[1]);
                })
                .toList();
    }

    /**
     * Finds the appropriate handler and extracts URL parameters if they exist.
     */
    public Handler findHandler(String method, String path, Map<String, String> extractedParams) {
        String exactKey = method.toUpperCase() + ":" + path;
        
        // 1. O(1) search for static routes (fastest)
        Handler exactMatch = routes.get(exactKey);
        if (exactMatch != null) {
            return exactMatch;
        }

        // 2. Linear search for dynamic routes with variables, e.g., /users/{id}
        String[] requestSegments = path.split("/");
        
        for (Map.Entry<String, Handler> entry : routes.entrySet()) {
            String routeKey = entry.getKey();
            String[] parts = routeKey.split(":", 2);
            String routeMethod = parts[0];
            String routePath = parts[1];
            
            if (!routeMethod.equals(method.toUpperCase())) {
                continue;
            }
            
            String[] routeSegments = routePath.split("/");
            
            // Routes must have the same number of segments
            if (requestSegments.length == routeSegments.length) {
                boolean match = true;
                for (int i = 0; i < routeSegments.length; i++) {
                    String rSeg = routeSegments[i];
                    String reqSeg = requestSegments[i];
                    
                    if (rSeg.startsWith("{") && rSeg.endsWith("}")) {
                        // It's a variable, extract the name without braces and populate the map
                        String paramName = rSeg.substring(1, rSeg.length() - 1);
                        extractedParams.put(paramName, reqSeg);
                    } else if (!rSeg.equals(reqSeg)) {
                        // Static segment didn't match, abort this route
                        match = false;
                        extractedParams.clear();
                        break;
                    }
                }
                
                if (match) {
                    return entry.getValue(); // Dynamic route found successfully
                }
            }
        }
        
        return null; // No route found (404)
    }

    public List<Middleware> getBeforeMiddlewares() {
        return beforeMiddlewares;
    }

    public List<AfterMiddleware> getAfterMiddlewares() {
        return afterMiddlewares;
    }
}
