package org.yardship.adapters.out.versionclient;

public class InvalidVersionResponseException extends RuntimeException{
    public InvalidVersionResponseException(String message) {
        super(message);
    }
}
