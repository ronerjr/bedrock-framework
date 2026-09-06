package com.bedrock.core;

/**
 * 🎓 BEDROCK TUTORIAL: Centralized Exception Handling
 * 
 * Functional interface used to intercept and handle specific exception types
 * in BedrockApp. When a controller or handler throws an exception of type E,
 * the registered ErrorHandler handles it, giving full control to set
 * HTTP status codes, headers, and semantic error responses.
 *
 * @param <E> The exception type to handle.
 */
@FunctionalInterface
public interface ErrorHandler<E extends Throwable> {

    /**
     * Handles the intercepted exception.
     *
     * @param ctx The HTTP request/response context.
     * @param exception The intercepted exception instance.
     */
    void handle(Context ctx, E exception);
}
