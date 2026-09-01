package org.urizo.axmodulestudio.backend.coding.service;

import java.util.List;
import java.util.Set;

/**
 * The fixed Coding guardrail Denylist.
 *
 * <p>These patterns live in code, not in the database, so no stored guardrail selection can grant
 * access to them. They apply to paths the AI changed; a human editing the same files is unaffected.
 *
 * <p>The list is repository-agnostic. Frontend patterns never match a Backend path and the reverse
 * also holds, so one flat list is enough until the stored per-repository selection arrives.
 */
final class GuardrailPathPolicy {

    /**
     * Repository-relative, {@code /}-separated paths as Git reports them in {@code changedPaths}.
     *
     * <p>A pattern without {@code /} matches the file name at any depth, so a Denylist entry cannot
     * be evaded by moving the file. A pattern with {@code /} matches the whole path, where
     * {@code **} spans zero or more segments and {@code *} spans any characters inside one segment.
     */
    private static final List<String> DENIED_PATTERNS = List.of(
            // Runtime composition and secrets. Real names differ from the conventional ones:
            // compose.dev.yaml and compose.dev-build-trust.yaml, not docker-compose.yml.
            "compose*.y*ml",
            "Dockerfile*",
            ".env*",
            "**/nginx/**",

            // Data. Preview runs flyway:validate after flyway:migrate, so editing one character of
            // an existing migration breaks preview startup against the restored schema history.
            "**/db/migration/**",

            // Backend packages. auth breaks every login with no way back in, coding would let the
            // controlled subject rewrite its own controls, orchestration and integration/ai would
            // let it choose the models it runs on, knowledge owns retrieval.
            "**/backend/auth/**",
            "**/backend/coding/**",
            "**/backend/orchestration/**",
            "**/backend/knowledge/**",
            "**/backend/integration/ai/**",

            // Frontend feature folders, for the same reasons as their Backend counterparts.
            "src/features/auth/**",
            "src/features/coding/**",
            "src/features/orchestration/**",
            "src/features/knowledge/**",

            // Frontend root files. These are files, not folders, so a folder scan never lists them
            // and they would otherwise never reach a stored selection.
            "index.html",
            "main.tsx",
            "vite.config.ts",
            "package.json",
            "pnpm-lock.yaml");

    private GuardrailPathPolicy() { }

    /**
     * Returns every denied path in {@code changedPaths}, in the order given.
     *
     * <p>The caller passes the paths Git actually reports, never what the model says it changed.
     */
    static List<String> deniedPaths(List<String> changedPaths) {
        return changedPaths.stream().filter(GuardrailPathPolicy::isDenied).toList();
    }

    static boolean isDenied(String path) {
        String normalized = normalize(path);
        if (normalized.isEmpty()) {
            return false;
        }
        return DENIED_PATTERNS.stream().anyMatch(pattern -> matches(pattern, normalized));
    }

    static Set<String> deniedPatterns() {
        return Set.copyOf(DENIED_PATTERNS);
    }

    /**
     * Strips a leading {@code ./} and collapses backslashes, so a Windows-shaped path cannot slip
     * past a pattern that the same path in Git form would match.
     */
    private static String normalize(String path) {
        String normalized = path.replace('\\', '/').trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static boolean matches(String pattern, String path) {
        String[] segments = path.split("/");
        if (!pattern.contains("/")) {
            return matchesSegment(pattern, segments[segments.length - 1]);
        }
        return matchesFrom(pattern.split("/"), 0, segments, 0);
    }

    private static boolean matchesFrom(
            String[] patternSegments, int patternIndex, String[] segments, int index) {
        if (patternIndex == patternSegments.length) {
            return index == segments.length;
        }
        if ("**".equals(patternSegments[patternIndex])) {
            for (int skipped = index; skipped <= segments.length; skipped++) {
                if (matchesFrom(patternSegments, patternIndex + 1, segments, skipped)) {
                    return true;
                }
            }
            return false;
        }
        if (index == segments.length
                || !matchesSegment(patternSegments[patternIndex], segments[index])) {
            return false;
        }
        return matchesFrom(patternSegments, patternIndex + 1, segments, index + 1);
    }

    /**
     * Matches one path segment against one pattern segment where {@code *} spans any characters.
     * Greedy scanning with backtracking, so {@code compose*.y*ml} matches
     * {@code compose.dev-build-trust.yaml}.
     */
    private static boolean matchesSegment(String pattern, String segment) {
        int patternIndex = 0;
        int index = 0;
        int starIndex = -1;
        int matchIndex = 0;
        while (index < segment.length()) {
            if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex;
                matchIndex = index;
                patternIndex++;
            } else if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == segment.charAt(index)) {
                patternIndex++;
                index++;
            } else if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                matchIndex++;
                index = matchIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
