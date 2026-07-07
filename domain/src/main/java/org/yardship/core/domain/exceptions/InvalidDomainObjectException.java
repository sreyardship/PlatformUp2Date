package org.yardship.core.domain.exceptions;

public class InvalidDomainObjectException extends RuntimeException{
    public InvalidDomainObjectException(String message) {
        super(message);
    }
}
