package org.yardship.adapters.out.versionclient;

public class VersionFetchException extends RuntimeException {

    private final int status;
    private final String body;

    public VersionFetchException(String message, int status, String body) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }
}
