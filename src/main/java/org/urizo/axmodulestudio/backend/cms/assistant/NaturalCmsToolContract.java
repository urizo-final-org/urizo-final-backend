package org.urizo.axmodulestudio.backend.cms.assistant;

import java.util.Map;
import java.util.Set;

public final class NaturalCmsToolContract {

    public static final Map<String, String> MODEL_TOOL_SCHEMA_DIGESTS = Map.of(
            "resolve_cms_target",
            "sha256:d746974fa9afd5e951f76f9af38954b0ad7f436f2120dc974da65e5ee39f856f",
            "validate_cms_command",
            "sha256:235a2af4b4dda4f961529d00e55bc45ea50b343c8a5f8317aba5a702f779d852",
            "create_cms_preview",
            "sha256:235a2af4b4dda4f961529d00e55bc45ea50b343c8a5f8317aba5a702f779d852");

    public static final Set<String> PREVIEW_TOOLS =
            Set.copyOf(MODEL_TOOL_SCHEMA_DIGESTS.keySet());

    private NaturalCmsToolContract() { }
}
