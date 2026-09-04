package org.urizo.axmodulestudio.backend.coding.service;

import java.util.Map;
import java.util.UUID;

/**
 * The repositories a Coding Job may work in, and the identifier its Job row stores.
 *
 * <p>A Job works in exactly one repository: its work folder is one checkout of one repository, so
 * a request that needs both the server and the screen is two requests. The stage that queues the
 * build has to know which one, and until now nothing recorded it — every Job was a backend Job.
 *
 * <p>There is no repository registry to point at, so {@code coding_job.repository_id} carries a
 * fixed identifier per repository rather than a foreign key. The backend value is deliberately the
 * one every Job written before this class already holds, so the existing history reads back as
 * backend without a migration and without a "when unknown, assume backend" default.
 */
public final class CodingRepositories {

    public static final String BACKEND = "backend";
    public static final String FRONTEND = "frontend";

    /** The value already stored on every Job created before repositories were told apart. */
    private static final UUID BACKEND_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FRONTEND_ID =
            UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final Map<String, UUID> IDENTIFIERS =
            Map.of(BACKEND, BACKEND_ID, FRONTEND, FRONTEND_ID);
    private static final Map<UUID, String> NAMES =
            Map.of(BACKEND_ID, BACKEND, FRONTEND_ID, FRONTEND);

    private CodingRepositories() {
    }

    /** Whether a name reached the server that this product knows how to work in. */
    public static boolean isKnown(String repository) {
        return repository != null && IDENTIFIERS.containsKey(repository);
    }

    public static UUID identifierOf(String repository) {
        UUID identifier = repository == null ? null : IDENTIFIERS.get(repository);
        if (identifier == null) {
            throw new IllegalArgumentException("Unknown Coding repository: " + repository);
        }
        return identifier;
    }

    /**
     * The repository a stored Job belongs to.
     *
     * <p>Refuses an identifier it did not write rather than naming a repository the Job may not be
     * in: the queued build checks out whatever this returns, and guessing would hand the model a
     * checkout of the wrong repository instead of failing where the wrong value came from.
     */
    public static String nameOf(UUID repositoryId) {
        String name = repositoryId == null ? null : NAMES.get(repositoryId);
        if (name == null) {
            throw new IllegalArgumentException("Unknown Coding repository id: " + repositoryId);
        }
        return name;
    }
}
