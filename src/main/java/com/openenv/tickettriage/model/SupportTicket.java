package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Represents a real-world IT support ticket in the environment.
 * Ground truth labels are hidden from the agent and used only by graders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportTicket {

    @JsonProperty("ticket_id")
    private String ticketId;

    private String title;
    private String description;

    @JsonProperty("user_name")
    private String userName;

    @JsonProperty("user_department")
    private String userDepartment;

    @JsonProperty("submitted_at")
    private String submittedAt;

    // Ground truth — NEVER sent to agent in observations
    @JsonProperty("ground_truth_priority")
    private Priority groundTruthPriority;

    @JsonProperty("ground_truth_category")
    private Category groundTruthCategory;

    @JsonProperty("ground_truth_team")
    private Team groundTruthTeam;

    @JsonProperty("ground_truth_resolution_hint")
    private String groundTruthResolutionHint;

    @JsonProperty("keywords")
    private List<String> keywords;

    // Enums matching the action space
    public enum Priority { LOW, MEDIUM, HIGH, CRITICAL }
    public enum Category { HARDWARE, SOFTWARE, NETWORK, SECURITY, ACCESS, OTHER }
    public enum Team { HELPDESK, SYSADMIN, NETWORK_OPS, SECURITY_OPS, DEVOPS, MANAGEMENT }
}
