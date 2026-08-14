package com.bedrock.core;

/**
 * Interface funcional que define um manipulador de requisições HTTP (Endpoint).
 */
@FunctionalInterface
public interface Handler {
    void handle(Context ctx) throws Exception;
}
