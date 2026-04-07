package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenEnv Action model.
 * Typed representation of what the agent submits at each step.
 *
 * Task coverage:
 * - EASY:   priority (required)
 * - MEDIUM: priority + category + assigned_team (required)
 * - HARD:   priority + category + assigned_team + resolution_suggestion
 *           + similar_ticket_ids + estimated_resolution_hours (required)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Action {

    /** Priority classification — required for all task types */
    @NotBlank(message = "priority is required")
    private String priority;

    /** Category classification — required for MEDIUM and HARD */
    private String category;

    /** Team assignment — required for MEDIUM and HARD */
    @JsonProperty("assigned_team")
    private String assignedTeam;

    /** Resolution suggestion — required for HARD */
    @JsonProperty("resolution_suggestion")
    private String resolutionSuggestion;

    /** Similar ticket IDs referenced — required for HARD */
    @JsonProperty("similar_ticket_ids")
    private List<String> similarTicketIds;

    /** Estimated resolution time in hours — required for HARD */
    @JsonProperty("estimated_resolution_hours")
    private Integer estimatedResolutionHours;

    /** Optional agent reasoning — never penalized, used for interpretability */
    @JsonProperty("reasoning")
    private String reasoning;
}
