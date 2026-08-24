package org.urizo.axmodulestudio.backend.integration.ai.gateway;

public final class CapabilityRegistrationException extends IllegalArgumentException {

    private final ModelGatewayErrorCode code;

    public CapabilityRegistrationException(ModelGatewayErrorCode code, String safeMessage) {
        super(safeMessage);
        this.code = code;
    }

    public ModelGatewayErrorCode code() {
        return code;
    }
}
