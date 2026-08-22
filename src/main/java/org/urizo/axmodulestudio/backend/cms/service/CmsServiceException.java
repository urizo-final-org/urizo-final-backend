package org.urizo.axmodulestudio.backend.cms.service;

public class CmsServiceException extends RuntimeException {

    public enum Kind {
        INVALID_REQUEST,
        NOT_FOUND
    }

    private final Kind kind;

    private CmsServiceException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public static CmsServiceException invalidRequest(String message) {
        return new CmsServiceException(Kind.INVALID_REQUEST, message);
    }

    public static CmsServiceException notFound(String message) {
        return new CmsServiceException(Kind.NOT_FOUND, message);
    }
}
