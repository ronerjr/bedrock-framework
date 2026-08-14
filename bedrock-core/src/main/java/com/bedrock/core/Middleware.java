package com.bedrock.core;

/**
 * Functional interface defining a Middleware to intercept requests before they reach the Handler.
 */
@FunctionalInterface
public interface Middleware {
    void handle(Context ctx) throws Exception;
}
