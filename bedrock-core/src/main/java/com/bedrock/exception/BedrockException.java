package com.bedrock.exception;

/**
 * Actionable Exception for the Bedrock framework.
 * 
 * DESIGN CONTEXT:
 * Beginners often struggle with massive Java StackTraces. 
 * Inspired by Spring Boot's FailureAnalyzer, BedrockException requires two arguments:
 * 1. Reason: What technically went wrong.
 * 2. Action: What the developer should actually do to fix it.
 * 
 * The output is beautifully formatted in the terminal to reduce frustration.
 */
public class BedrockException extends RuntimeException {

    public BedrockException(String reason, String action) {
        super(formatMessage(reason, action));
    }

    public BedrockException(String reason, String action, Throwable cause) {
        super(formatMessage(reason, action), cause);
    }

    private static String formatMessage(String reason, String action) {
        return """
               
               **************************************************************
               \uD83E\uDD96 BEDROCK FATAL ERROR: Framework execution halted
               **************************************************************
               [Reason]: %s
               
               [Action Required]: 
               %s
               **************************************************************
               """.formatted(reason, action);
    }
}
