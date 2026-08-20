package com.bedrock.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Maps a method to respond to HTTP POST requests on a specific route.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface BedrockPost {
    /**
     * The route path (e.g., "/api/users")
     */
    String value();
}
