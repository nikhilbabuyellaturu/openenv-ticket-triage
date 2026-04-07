package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * OpenEnv Observation model.
 * Typed representation of what the agent sees at each step.
 * Ground truth labels are NEVER included here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Observation {

    // Current ticket details (agent-visible)
    @JsonProperty("ticket_id")
    private String ticketId;

    @JsonProperty("ticket_title")
    private String ticketTitle;

    @JsonProperty("ticket_description")
    private String ticketDescription;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("user_department")
    private String userDepartment;

    @JsonProperty("submitted_at")
    private String submittedAt;

    // Task context
    @JsonProperty("task_type")
    private TaskType taskType;

    @JsonProperty("task_description")
    private String taskDescription;

    @JsonProperty("step_count")
    private int stepCount;

    @JsonProperty("max_steps")
    private int maxSteps;

    @JsonProperty("done")
    private boolean done;

    // History of agent actions in this episode
    @JsonProperty("previous_actions")
    private List<Map<String, Object>> previousActions;

    // Cumulative reward so far
    @JsonProperty("cumulative_reward")
    private double cumulativeReward;

    // Available action schema — tells agent what fields it can fill
    @JsonProperty("action_schema")
    private ActionSchema actionSchema;

    // Similar tickets shown to agent for HARD task
    @JsonProperty("similar_tickets")
    private List<SimilarTicketSummary> similarTickets;

    public enum TaskType { EASY, MEDIUM, HARD }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionSchema {
        @JsonProperty("required_fields")
        private List<String> requiredFields;

        @JsonProperty("optional_fields")
        private List<String> optionalFields;

        @JsonProperty("valid_priorities")
        private List<String> validPriorities;

        @JsonProperty("valid_categories")
        private List<String> validCategories;

        @JsonProperty("valid_teams")
        private List<String> validTeams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimilarTicketSummary {
        @JsonProperty("ticket_id")
        private String ticketId;

        @JsonProperty("title")
        private String title;

        @JsonProperty("resolution_summary")
        private String resolutionSummary;

        @JsonProperty("resolved_in_hours")
        private int resolvedInHours;
    }
}
