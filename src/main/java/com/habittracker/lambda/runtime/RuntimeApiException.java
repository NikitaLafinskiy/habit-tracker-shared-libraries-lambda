package com.habittracker.lambda.runtime;

public class RuntimeApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private static final int CONTAINER_ERROR_STATUS = 500;

    private final int statusCode;

    RuntimeApiException(String path, int statusCode) {
        super("Lambda Runtime API " + path + " answered HTTP " + statusCode);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public boolean isContainerError() {
        return statusCode >= CONTAINER_ERROR_STATUS;
    }
}
