package com.openenv.tickettriage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openenv.tickettriage.model.Action;
import com.openenv.tickettriage.model.ResetRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the full OpenEnv episode lifecycle.
 * Tests: reset → step → state for each task type.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OpenEnvIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    private static final String RESET = "/api/v1/reset";
    private static final String STEP  = "/api/v1/step";
    private static final String STATE = "/api/v1/state";

    // ──────────────────────────────────────────────────────────
    // Health & Info
    // ──────────────────────────────────────────────────────────

    @Test @Order(1)
    @DisplayName("Health endpoint returns UP")
    void healthCheck() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("1.0.0"));
    }

    @Test @Order(2)
    @DisplayName("Info endpoint returns task definitions")
    void infoEndpoint() throws Exception {
        mvc.perform(get("/api/v1/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_types", hasSize(3)))
                .andExpect(jsonPath("$.reward_range[0]").value(0.0))
                .andExpect(jsonPath("$.reward_range[1]").value(1.0));
    }

    // ──────────────────────────────────────────────────────────
    // EASY Task
    // ──────────────────────────────────────────────────────────

    @Test @Order(10)
    @DisplayName("EASY: reset returns observation with task_type EASY")
    void easyReset() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("EASY").ticketId("TKT-001").seed(42L).build();

        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_type").value("EASY"))
                .andExpect(jsonPath("$.ticket_id").value("TKT-001"))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.action_schema.required_fields", contains("priority")))
                .andExpect(jsonPath("$.action_schema.valid_priorities",
                        hasItems("LOW","MEDIUM","HIGH","CRITICAL")));
    }

    @Test @Order(11)
    @DisplayName("EASY: correct priority classification yields full reward")
    void easyCorrectPriority() throws Exception {
        // Reset first
        ResetRequest req = ResetRequest.builder()
                .taskType("EASY").ticketId("TKT-001").seed(42L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        // TKT-001 ground truth = HIGH
        Action action = Action.builder().priority("HIGH").reasoning("Screen flickering with deadline is HIGH").build();

        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reward.value").value(1.0))
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.reward.component_scores.priority_score").value(1.0));
    }

    @Test @Order(12)
    @DisplayName("EASY: one level off priority yields partial reward")
    void easyNearMissPriority() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("EASY").ticketId("TKT-001").seed(42L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        // TKT-001 = HIGH, submitting CRITICAL (1 level off)
        Action action = Action.builder().priority("CRITICAL").build();

        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reward.value").value(0.5))
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test @Order(13)
    @DisplayName("EASY: invalid priority value applies penalty")
    void easyInvalidPriority() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("EASY").ticketId("TKT-001").seed(42L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        Action action = Action.builder().priority("URGENT").build();

        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reward.value").value(0.0))
                .andExpect(jsonPath("$.reward.penalty_applied").value(0.1))
                .andExpect(jsonPath("$.done").value(true));
    }

    // ──────────────────────────────────────────────────────────
    // MEDIUM Task
    // ──────────────────────────────────────────────────────────

    @Test @Order(20)
    @DisplayName("MEDIUM: reset returns observation requiring priority, category, team")
    void mediumReset() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("MEDIUM").ticketId("TKT-004").seed(42L).build();

        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_type").value("MEDIUM"))
                .andExpect(jsonPath("$.action_schema.required_fields",
                        hasItems("priority","category","assigned_team")));
    }

    @Test @Order(21)
    @DisplayName("MEDIUM: perfect action on TKT-004 yields score close to 1.0")
    void mediumPerfectAction() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("MEDIUM").ticketId("TKT-004").seed(42L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        // TKT-004: CRITICAL / SOFTWARE / SYSADMIN
        Action action = Action.builder()
                .priority("CRITICAL")
                .category("SOFTWARE")
                .assignedTeam("SYSADMIN")
                .reasoning("SAP crash on all Finance machines is CRITICAL SOFTWARE issue for SYSADMIN")
                .build();

        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reward.value").value(closeTo(1.0, 0.01)))
                .andExpect(jsonPath("$.reward.component_scores.priority_score").value(0.40))
                .andExpect(jsonPath("$.reward.component_scores.category_score").value(0.35))
                .andExpect(jsonPath("$.reward.component_scores.team_score").value(0.25))
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test @Order(22)
    @DisplayName("MEDIUM: missing team field applies penalty")
    void mediumMissingTeam() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("MEDIUM").ticketId("TKT-004").seed(42L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        Action action = Action.builder().priority("CRITICAL").category("SOFTWARE").build();

        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reward.penalty_applied").value(greaterThan(0.0)))
                .andExpect(jsonPath("$.done").value(true));
    }

    // ──────────────────────────────────────────────────────────
    // HARD Task
    // ──────────────────────────────────────────────────────────

    @Test @Order(30)
    @DisplayName("HARD: reset shows similar tickets in observation")
    void hardReset() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("HARD").ticketId("TKT-010").seed(42L).build();

        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task_type").value("HARD"))
                .andExpect(jsonPath("$.max_steps").value(3))
                .andExpect(jsonPath("$.similar_tickets").isArray())
                .andExpect(jsonPath("$.action_schema.required_fields",
                        hasItems("priority","category","assigned_team",
                                 "resolution_suggestion","similar_ticket_ids","estimated_resolution_hours")));
    }

    @Test @Order(31)
    @DisplayName("HARD: multi-step episode — step count increments correctly")
    void hardMultiStep() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("HARD").ticketId("TKT-010").seed(42L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        // TKT-010: CRITICAL / SECURITY / SECURITY_OPS (phishing + credential compromise)
        Action action = Action.builder()
                .priority("CRITICAL")
                .category("SECURITY")
                .assignedTeam("SECURITY_OPS")
                .resolutionSuggestion("Reset user credentials immediately, revoke active sessions, check audit logs for unauthorized access, notify CISO, file incident report.")
                .similarTicketIds(List.of("TKT-011", "TKT-012"))
                .estimatedResolutionHours(2)
                .reasoning("Phishing credential compromise is a CRITICAL SECURITY incident")
                .build();

        // Step 1
        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.step_count").value(1))
                .andExpect(jsonPath("$.done").value(false))
                .andExpect(jsonPath("$.reward.value").value(greaterThan(0.0)));

        // Step 2
        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.step_count").value(2))
                .andExpect(jsonPath("$.done").value(false));

        // Step 3 — final
        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.step_count").value(3))
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test @Order(32)
    @DisplayName("HARD: stepping after done returns error in reward feedback")
    void hardStepAfterDone() throws Exception {
        // Episode is done from previous test — step again
        Action action = Action.builder().priority("HIGH").build();

        mvc.perform(post(STEP).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(action)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true))
                .andExpect(jsonPath("$.reward.feedback",
                        containsString("already done")));
    }

    // ──────────────────────────────────────────────────────────
    // State
    // ──────────────────────────────────────────────────────────

    @Test @Order(40)
    @DisplayName("state() includes ground truth labels after episode")
    void stateIncludesGroundTruth() throws Exception {
        ResetRequest req = ResetRequest.builder()
                .taskType("EASY").ticketId("TKT-007").seed(1L).build();
        mvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));

        mvc.perform(get(STATE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ground_truth.priority").value("CRITICAL"))
                .andExpect(jsonPath("$.ground_truth.category").value("NETWORK"))
                .andExpect(jsonPath("$.ground_truth.team").value("NETWORK_OPS"))
                .andExpect(jsonPath("$.ground_truth.resolution_hint").isNotEmpty())
                .andExpect(jsonPath("$.current_ticket_id").value("TKT-007"));
    }

    // ──────────────────────────────────────────────────────────
    // Tickets endpoint
    // ──────────────────────────────────────────────────────────

    @Test @Order(50)
    @DisplayName("Tickets endpoint returns 15 tickets without ground truth")
    void ticketsEndpoint() throws Exception {
        mvc.perform(get("/api/v1/tickets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(15)))
                .andExpect(jsonPath("$[0].ticket_id").exists())
                .andExpect(jsonPath("$[0].ground_truth_priority").doesNotExist());
    }
}
