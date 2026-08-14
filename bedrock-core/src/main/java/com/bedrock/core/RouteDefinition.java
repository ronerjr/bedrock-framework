package com.bedrock.core;

/**
 * Represents the definition of a registered route in the system.
 * It uses the Java Records feature for immutability and conciseness.
 */
public record RouteDefinition(String method, String path) {
}
