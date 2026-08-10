package com.virtual.exception;

public class GameDBException extends RuntimeException {
    public GameDBException() {}

    public GameDBException(String message) {
        super(message);
    }

    public GameDBException(String message, Throwable cause) {
        super(message, cause);
    }
}
