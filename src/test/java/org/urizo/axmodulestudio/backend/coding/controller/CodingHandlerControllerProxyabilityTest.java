package org.urizo.axmodulestudio.backend.coding.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;

import org.junit.jupiter.api.Test;

class CodingHandlerControllerProxyabilityTest {

    @Test
    void validatedCodingHandlerControllersRemainProxyable() {
        assertThat(Modifier.isFinal(CodingHandlerCommandController.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(CodingHandlerResultController.class.getModifiers())).isFalse();
    }
}
