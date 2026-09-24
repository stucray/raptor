package com.stucray.raptor.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stucray.raptor.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The capture verdict on a host where the config file is not mounted.
 *
 * <p>Worth its own test because the config is an out-of-jar artifact: the
 * failure it guards against is a deployment that forgot the mount, where
 * a 500 would take the whole health screen down with it. The screen keeps
 * answering and says nothing about intent it cannot read.
 *
 * <p>Since S10 the verdict does not need the config at all — it is derived from
 * scope, not from a fire hour — so an unreadable config now costs only the
 * "configured capture" panel rather than the freshness rule as well.
 */
@SpringBootTest(properties = {
    "raptor.capture.config-file=/no/such/capture.properties"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@DisplayName("Capture summary without a readable config")
class CaptureSummaryWithoutConfigTest {

    @Autowired MockMvc mvc;

    @Test
    void theScreenStillAnswersAndOmitsTheConfiguredCapture() throws Exception {
        mvc.perform(get("/api/health/capture-summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").doesNotExist())
            // The verdict still answers from scope, which needs no config file.
            .andExpect(jsonPath("$.lostFixtures").value(0))
            .andExpect(jsonPath("$.scope").exists());
    }
}
