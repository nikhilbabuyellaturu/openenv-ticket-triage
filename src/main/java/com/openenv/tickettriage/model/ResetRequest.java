package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /reset
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetRequest {

    /**
     * Task type to initialize: EASY, MEDIUM, HARD
     * Defaults to EASY if not specified.
     */
    @JsonProperty("task_type")
    private String taskType;

    /**
     * Optional specific ticket ID to use (for reproducible evaluation).
     * If null, a ticket is chosen randomly for the task type.
     */
    @JsonProperty("ticket_id")
    private String ticketId;

    /**
     * Optional seed for reproducibility.
     */
    private Long seed;
}
