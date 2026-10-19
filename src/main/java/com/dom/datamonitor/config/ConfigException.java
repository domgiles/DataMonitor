package com.dom.datamonitor.config;

/**
 * Thrown when the application configuration is invalid or cannot be loaded.
 * Messages are written to be user-friendly (displayed directly to the operator).
 */
public class ConfigException extends Exception {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
