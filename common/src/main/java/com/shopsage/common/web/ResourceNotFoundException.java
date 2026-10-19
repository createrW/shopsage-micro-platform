package com.shopsage.common.web;

/** The requested resource does not exist (or is not visible to the caller); mapped to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
