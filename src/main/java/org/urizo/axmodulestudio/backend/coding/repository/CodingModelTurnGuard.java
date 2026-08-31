package org.urizo.axmodulestudio.backend.coding.repository;

import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnContract;
import org.urizo.axmodulestudio.backend.coding.dto.CodingModelTurnPermit;
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
        fail(permit, failureCode, retryable, null);
    }

    /**
     * @param diagnostic structural facts about a rejected reply, or null. A failed turn
     *     keeps no reply, so this is the only record of why a contract miss happened.
     *     It must never carry prompt text, tool output or generated code.
     */
    default void fail(
            CodingModelTurnPermit permit,
            String failureCode,
            boolean retryable,
            com.fasterxml.jackson.databind.JsonNode diagnostic) {
        throw new UnsupportedOperationException("Model Turn failure persistence is not implemented.");
    }
}
