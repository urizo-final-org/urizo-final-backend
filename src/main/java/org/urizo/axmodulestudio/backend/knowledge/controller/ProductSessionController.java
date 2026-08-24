package org.urizo.axmodulestudio.backend.knowledge.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.knowledge.dto.ProductApiContract;
import org.urizo.axmodulestudio.backend.knowledge.security.ProductLocalAccess;

@RestController
@Profile("local-full & dev-session")
@RequestMapping("/internal/dev/product-session")
final class ProductSessionController {

    private final ProductLocalAccess access;

    ProductSessionController(ProductLocalAccess access) {
        this.access = access;
    }

    @GetMapping
    ProductApiContract.ProductSessionResponse session() {
        return access.session();
    }
}
