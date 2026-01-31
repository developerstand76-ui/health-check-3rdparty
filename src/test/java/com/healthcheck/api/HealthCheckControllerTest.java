package com.healthcheck.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthcheck.model.*;
import com.healthcheck.service.HealthCheckService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HealthCheckController.class)
public class HealthCheckControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private HealthCheckService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createTargetReturnsCreated() throws Exception {
        CreateTargetRequest request = new CreateTargetRequest();
        request.setName("API");
        request.setUrl("https://example.com/health");
        request.setMethod(HttpMethod.GET);
        request.setTimeout(Duration.ofSeconds(2));
        request.setExpectedStatusMin(200);
        request.setExpectedStatusMax(299);
        request.setExpectJson(false);
        request.setSlowThreshold(Duration.ofSeconds(1));
        request.setMaxRetries(1);

        Target target = new Target(UUID.randomUUID(), "API", "https://example.com/health", HttpMethod.GET,
            Map.of(), null, null, Duration.ofSeconds(2), 200, 299, false, null, Duration.ofSeconds(1), 1);

        when(service.createTarget(org.mockito.ArgumentMatchers.any(CreateTargetRequest.class))).thenReturn(target);

        mockMvc.perform(post("/api/targets")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id", notNullValue()))
            .andExpect(jsonPath("$.name", is("API")));
    }

    @Test
    void getTargetsReturnsList() throws Exception {
        Target target = new Target(UUID.randomUUID(), "API", "https://example.com/health", HttpMethod.GET,
            Map.of(), null, null, Duration.ofSeconds(2), 200, 299, false, null, Duration.ofSeconds(1), 1);
        when(service.listTargets()).thenReturn(List.of(target));

        mockMvc.perform(get("/api/targets"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void checkTargetNotFound() throws Exception {
        when(service.checkTarget(org.mockito.ArgumentMatchers.any(UUID.class), eq(false))).thenReturn(null);

        mockMvc.perform(post("/api/targets/" + UUID.randomUUID() + "/check"))
            .andExpect(status().isNotFound());
    }
}
