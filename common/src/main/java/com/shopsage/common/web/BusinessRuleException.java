package com.shopsage.common.web;

/**
 * A well-formed request that conflicts with current state or a domain rule (e.g. insufficient stock,
 * order not cancellable); mapped to HTTP 409. Its message is shown to the client, so keep it non-sensitive.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
