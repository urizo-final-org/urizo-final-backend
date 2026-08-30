package org.urizo.axmodulestudio.backend.integration.ai.mcp;

public final class McpPlatformException extends RuntimeException {

    public McpPlatformException(String message) {
        super(message);
    }

    public McpPlatformException(String message, Throwable cause) {
        super(message, cause);
    }
}
