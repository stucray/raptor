package com.stucray.raptor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// Spool pointed at a non-existent path so the startup catch-up no-ops
// instead of importing whatever real spool exists on the dev machine.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RaptorApplicationTests {

	@Test
	void contextLoads() {
	}

}
