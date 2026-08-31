package org.urizo.axmodulestudio.backend.integration.ai.mcp;

import java.util.Map;
import java.util.Set;

public final class McpPlatformContract {

    public static final String PROTOCOL_VERSION = "2026-07-28";
    public static final String SERVER_NAME = "urizo-final-mcp-server";
    public static final String CLIENT_NAME = "ax-module-studio-backend";

    private static final Map<String, String> ALLOWED_TOOL_PACKAGES = Map.ofEntries(
            Map.entry("read_file", "coding"),
            Map.entry("search_code", "coding"),
            Map.entry("read_diff", "coding"),
            Map.entry("apply_patch", "coding"),
            Map.entry("run_check", "coding"),
            Map.entry("check_package_allowlist", "coding"),
            Map.entry("scan_changed_files", "coding"),
            Map.entry("resolve_cms_target", "cms"),
            Map.entry("validate_cms_command", "cms"),
            Map.entry("create_cms_preview", "cms"),
            Map.entry("discard_cms_preview", "cms"),
            Map.entry("revalidate_cms_preview", "cms"),
            Map.entry("apply_cms_preview", "cms"));

    private static final Set<String> CODING_TOOL_NAMES = Set.of(
            "read_file",
            "search_code",
            "read_diff",
            "apply_patch",
            "run_check",
            "check_package_allowlist",
            "scan_changed_files");

    private static final Set<String> CMS_TOOL_NAMES = Set.of(
            "resolve_cms_target",
            "validate_cms_command",
            "create_cms_preview",
            "discard_cms_preview",
            "revalidate_cms_preview",
            "apply_cms_preview");

    private McpPlatformContract() {
    }

    public static Set<String> allowedToolNames() {
        return ALLOWED_TOOL_PACKAGES.keySet();
    }

    public static String packageFor(String toolName) {
        return ALLOWED_TOOL_PACKAGES.get(toolName);
    }

    public static Set<String> codingToolNames() {
        return CODING_TOOL_NAMES;
    }

    public static Set<String> cmsToolNames() {
        return CMS_TOOL_NAMES;
    }

    public record Snapshot(String protocolVersion, String serverName, Set<String> exposedTools) {

        public Snapshot {
            exposedTools = Set.copyOf(exposedTools);
        }
    }
}
