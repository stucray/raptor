package com.stucray.raptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

@DisplayName("Application module boundaries hold")
class ApplicationModuleArchitectureTest {

    @Test
    void verify() {
        ApplicationModules.of(RaptorApplication.class).verify();
    }
}
