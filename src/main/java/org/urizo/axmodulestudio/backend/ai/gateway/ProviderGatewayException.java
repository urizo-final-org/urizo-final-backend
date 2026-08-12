package org.urizo.axmodulestudio.backend.ai.gateway;

public final class ProviderGatewayException extends RuntimeException {

    private final ModelGatewayErrorCode code;

    public ProviderGatewayException(ModelGatewayErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ModelGatewayErrorCode code() {
        return code;
    }
}
