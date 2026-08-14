package org.urizo.axmodulestudio.backend.product;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
