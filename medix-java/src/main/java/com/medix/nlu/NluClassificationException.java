package com.medix.nlu;

public class NluClassificationException extends RuntimeException {
    public NluClassificationException(String message) {
        super(message);
    }

    public NluClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
