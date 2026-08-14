package com.bedrock.core;

/**
 * Functional interface defining a Middleware that intercepts requests after the Handler,
 * allowing manipulation of the response headers before the final flush.
 */
@FunctionalInterface
public interface AfterMiddleware {
    void handle(Context ctx) throws Exception;
}
