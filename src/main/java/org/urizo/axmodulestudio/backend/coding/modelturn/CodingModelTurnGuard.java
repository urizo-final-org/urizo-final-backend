package org.urizo.axmodulestudio.backend.coding.modelturn;

/**
 * Future activation boundary for rotating service authentication and
 * authoritative Core-DB Job/idempotency/state-version authorization.
 *
 * No permissive implementation is provided. Enabling the endpoint without an
 * explicit guard therefore fails application startup.
 */
@FunctionalInterface
public interface CodingModelTurnGuard {

    CodingModelTurnPermit reserve(String authorizationHeader, CodingModelTurnContract.Request request);

    default void complete(CodingModelTurnPermit permit, CodingModelTurnContract.Response response) {
        throw new UnsupportedOperationException("Model Turn completion is not implemented.");
    }

    default void fail(CodingModelTurnPermit permit, String failureCode, boolean retryable) {
        throw new UnsupportedOperationException("Model Turn failure persistence is not implemented.");
    }
}
