package com.bedrock.core;

/**
 * Educational Logger for Bedrock Java.
 * 
 * DESIGN CONTEXT:
 * Real-world applications use robust logging frameworks like SLF4J or Logback.
 * To keep Bedrock true to its "Zero Dependencies" promise, we implemented our own
 * minimalist logger. It teaches the concept of Log Levels (INFO, WARN, ERROR)
 * and uses ANSI Escape Codes to colorize terminal output.
 */
public class BedrockLogger {

    // ANSI Escape Codes for coloring terminal output
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";

    public static void info(String component, String message) {
        System.out.println(GREEN + "[INFO] [" + component + "] " + RESET + message);
    }

    public static void warn(String component, String message) {
        System.out.println(YELLOW + "[WARN] [" + component + "] " + RESET + message);
    }

    public static void error(String component, String message) {
        System.err.println(RED + "[ERROR] [" + component + "] " + RESET + message);
    }

    public static void banner(String message) {
        System.out.println(CYAN + message + RESET);
    }
}
