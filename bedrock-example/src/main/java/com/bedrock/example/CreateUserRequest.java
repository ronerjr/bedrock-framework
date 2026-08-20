package com.bedrock.example;

/**
 * 🎓 BEDROCK TUTORIAL: Request DTOs with Java Records
 * 
 * Bedrock automatically deserializes incoming JSON payloads
 * into Java Records using reflection and canonical constructors!
 */
public record CreateUserRequest(String name, String level) {
}
