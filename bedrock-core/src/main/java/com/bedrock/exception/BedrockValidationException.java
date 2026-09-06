package com.bedrock.exception;

/**
 * 🎓 BEDROCK TUTORIAL: Actionable Validation Errors
 * 
 * Thrown when incoming request data (path parameters, query parameters, headers, or body)
 * violates validation constraints or cannot be coerced into the expected type.
 * 
 * By convention in Bedrock, unhandled BedrockValidationExceptions produce an HTTP 400 Bad Request
 * response containing the actionable reason.
 */
public class BedrockValidationException extends BedrockException {

    private final String parameterName;
    private final Object invalidValue;

    public BedrockValidationException(String parameterName, Object invalidValue, String reason, String action) {
        super(reason, action);
        this.parameterName = parameterName;
        this.invalidValue = invalidValue;
    }

    public String getParameterName() {
        return parameterName;
    }

    public Object getInvalidValue() {
        return invalidValue;
    }
}
