package com.openenv.tickettriage.environment;

import com.openenv.tickettriage.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenEnv REST API Controller.
 * POST /reset  → initial observation
 * POST /step   → observation, reward, done, info
 * GET  /state  → full environment state (with ground truth)
 * GET  /tickets → all tickets (no ground truth exposed)
 * GET  /info   → environment metadata
 * GET  /health → health check
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "OpenEnv Ticket Triage", description = "IT Support Ticket Triage RL Environment")
public class OpenEnvController {

    private final EnvironmentService environmentService;
    private final TicketDataStore ticketDataStore;

    @PostMapping("/reset")
    @Operation(summary = "Reset environment",
               description = "Starts a new episode. task_type: EASY | MEDIUM | HARD")
    public ResponseEntity<Observation> reset(
            @RequestBody(required = false) ResetRequest request) {
        if (request == null) request = new ResetRequest();
        return ResponseEntity.ok(environmentService.reset(request));
    }

    @PostMapping("/step")
    @Operation(summary = "Take a step",
               description = "Submit an action. Returns observation, reward, done, info.")
    public ResponseEntity<StepResponse> step(@RequestBody @Valid Action action) {
        return ResponseEntity.ok(environmentService.step(action));
    }

    @GetMapping("/state")
    @Operation(summary = "Get environment state",
               description = "Full state including ground truth labels for evaluation/debugging.")
    public ResponseEntity<EnvironmentState> state() {
        return ResponseEntity.ok(environmentService.state());
    }

    @GetMapping("/tickets")
    @Operation(summary = "List all tickets",
               description = "Returns all 15 tickets (no ground truth labels).")
    public ResponseEntity<List<Map<String, Object>>> tickets() {
        List<Map<String, Object>> result = ticketDataStore.findAll().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("ticket_id", t.getTicketId());
                    m.put("title", t.getTitle());
                    m.put("user_name", t.getUserName());
                    m.put("user_department", t.getUserDepartment());
                    m.put("submitted_at", t.getSubmittedAt());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "environment", "ticket-triage-v1",
                "version", "1.0.0"
        ));
    }

    @GetMapping("/info")
    @Operation(summary = "Environment metadata")
    public ResponseEntity<Map<String, Object>> info() {
        return ResponseEntity.ok(Map.of(
                "name", "IT Support Ticket Triage",
                "version", "1.0.0",
                "task_types", List.of(
                        Map.of("id", "EASY",   "description", "Priority classification only", "max_steps", 1, "difficulty", "easy"),
                        Map.of("id", "MEDIUM", "description", "Priority + category + team",   "max_steps", 1, "difficulty", "medium"),
                        Map.of("id", "HARD",   "description", "Full triage with resolution",  "max_steps", 3, "difficulty", "hard")
                ),
                "action_space", Map.of(
                        "type", "structured_json",
                        "fields", List.of("priority", "category", "assigned_team",
                                "resolution_suggestion", "similar_ticket_ids",
                                "estimated_resolution_hours", "reasoning")
                ),
                "observation_space", Map.of(
                        "type", "structured_json",
                        "fields", List.of("ticket_id", "ticket_title", "ticket_description",
                                "user_name", "user_department", "submitted_at", "task_type",
                                "task_description", "step_count", "max_steps", "done",
                                "previous_actions", "cumulative_reward", "action_schema", "similar_tickets")
                ),
                "reward_range", List.of(0.0, 1.0),
                "ticket_count", ticketDataStore.findAll().size()
        ));
    }
}
