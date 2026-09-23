package com.stucray.raptor;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModuleArchitectureTest {

	static final ApplicationModules MODULES = ApplicationModules.of(AcquisitionTestApplication.class);

	@Test
	void verifiesModuleStructure() {
		MODULES.verify();
	}
}
