package org.urizo.axmodulestudio.backend.common.auth;

/**
 * Minimum permission matrix of the Auth/RBAC MVP.
 *
 * <p>Each constant mirrors one row of the approved specification. Two scopes cover the whole matrix:
 * a platform-global operation stays in the delivery-company technical lane, and a Project-scoped
 * operation is reachable by an assigned {@code GENERAL_ADMIN} and by {@code SUPER_ADMIN} as a
 * support override.
 */
public enum AdminPermission {

    /** List and open Projects. */
    PROJECT_OPEN(Scope.PROJECT_SCOPED),
    /** Create or archive a Project. */
    PROJECT_CREATE_OR_ARCHIVE(Scope.PLATFORM_GLOBAL),
    /** Assign or remove a {@code GENERAL_ADMIN} Project membership. */
    PROJECT_MEMBERSHIP_ASSIGN(Scope.PLATFORM_GLOBAL),
    /** Create, disable, or restore an administrator account. */
    ADMIN_ACCOUNT_MANAGE(Scope.PLATFORM_GLOBAL),
    /** Grant or revoke {@code SUPER_ADMIN} through the protected technical path. */
    SUPER_ADMIN_GRANT(Scope.PLATFORM_GLOBAL),

    /** Connector URL, method, and request/response specification. */
    CONNECTOR_SPECIFICATION(Scope.PLATFORM_GLOBAL),
    /** Public-data, platform LLM, or LangSmith Secret registration and rotation. */
    PLATFORM_SECRET_MANAGE(Scope.PLATFORM_GLOBAL),
    /** Connector connection test and Connector Version activation. */
    CONNECTOR_TEST_OR_ACTIVATE(Scope.PLATFORM_GLOBAL),
    /** Provider/model allowlist and platform capability configuration. */
    PROVIDER_ALLOWLIST_MANAGE(Scope.PLATFORM_GLOBAL),
    /** Repository status, PathPolicy, fixed denylist, and infrastructure health. */
    REPOSITORY_AND_PATH_POLICY(Scope.PLATFORM_GLOBAL),
    /** Use or select an already activated Connector/model inside a Project. */
    ACTIVATED_CONNECTOR_USE(Scope.PROJECT_SCOPED),

    /** Configure non-secret document mapping, chunking, and evaluation criteria. */
    RAG_COMPOSITION_CONFIGURE(Scope.PROJECT_SCOPED),
    /** View evaluation results and run RAG query tests. */
    RAG_EVALUATION_VIEW(Scope.PROJECT_SCOPED),
    /** Connect a Chatbot to Project Knowledge and test it. */
    CHATBOT_CONNECT_AND_TEST(Scope.PROJECT_SCOPED),
    /** Start, cancel, or retry a Knowledge Build. */
    KNOWLEDGE_BUILD_CONTROL(Scope.PLATFORM_GLOBAL),
    /** Activate or roll back a Knowledge Version. */
    KNOWLEDGE_VERSION_TRANSITION(Scope.PLATFORM_GLOBAL),

    /** Menu create, update, delete, preview, publish, and unpublish. */
    MENU_MANAGE(Scope.PROJECT_SCOPED),
    /** Content/Page create, update, delete, preview, publish, and unpublish. */
    CONTENT_MANAGE(Scope.PROJECT_SCOPED),
    /** Board/Post create, update, delete, publish, and unpublish. */
    BOARD_MANAGE(Scope.PROJECT_SCOPED),
    /** Site design/template configuration, preview, publish, and rollback. */
    SITE_DESIGN_MANAGE(Scope.PROJECT_SCOPED),
    /** Customer business-member data management. */
    BUSINESS_MEMBER_MANAGE(Scope.PROJECT_SCOPED);

    private final Scope scope;

    AdminPermission(Scope scope) {
        this.scope = scope;
    }

    /** Whether the operation belongs to the delivery-company technical lane. */
    public boolean isPlatformGlobal() {
        return scope == Scope.PLATFORM_GLOBAL;
    }

    /** Whether the operation is evaluated against one target Project. */
    public boolean isProjectScoped() {
        return scope == Scope.PROJECT_SCOPED;
    }

    private enum Scope {
        PLATFORM_GLOBAL,
        PROJECT_SCOPED
    }
}
