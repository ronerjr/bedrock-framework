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

    private final String reason;
    private final String action;

    public BedrockException(String reason, String action) {
        super(formatMessage(reason, action));
        this.reason = reason;
        this.action = action;
    }

    public BedrockException(String reason, String action, Throwable cause) {
        super(formatMessage(reason, action), cause);
        this.reason = reason;
        this.action = action;
    }

    public String getReason() {
        return reason;
    }

    public String getAction() {
        return action;
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
