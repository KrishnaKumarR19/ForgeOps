package com.forgeops.identity.application;

/**
 * Raised for any authentication failure during login: unknown user, wrong password, or
 * disabled account. The failure is deliberately <strong>indistinguishable</strong> across
 * these cases to resist user enumeration (SECURITY_DESIGN.md §6). Its message is generic
 * and never reveals which factor failed or whether the user exists.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
