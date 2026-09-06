package com.bedrock.example;

/**
 * 🎓 BEDROCK TUTORIAL: Domain Business Exception
 * 
 * Instead of manually catching errors and formatting 404 responses in every controller method,
 * your business logic can throw clear domain exceptions.
 * Bedrock's global exception handler (app.onError) intercepts it and formats the HTTP response!
 */
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String id) {
        super("User with id '" + id + "' was not found.");
    }
}
