package org.urizo.axmodulestudio.backend.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TemplateHeroImage(
        @NotBlank @Size(max = 500) String url,
        @Size(max = 120) String title,
        @Size(max = 240) String description) {
    public TemplateHeroImage {
        title = title == null ? "" : title;
        description = description == null ? "" : description;
    }
}
