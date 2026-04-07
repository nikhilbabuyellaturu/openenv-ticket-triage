package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Full environment state — returned by state() endpoint.
 * Includes ground truth for debugging/evaluation (not exposed during live episode).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentState {

    @JsonProperty("episode_id")
    private String episodeId;

    @JsonProperty("task_type")
    private Observation.TaskType taskType;

    @JsonProperty("current_ticket_id")
    private String currentTicketId;

    @JsonProperty("step_count")
    private int stepCount;

    @JsonProperty("max_steps")
    private int maxSteps;

    @JsonProperty("done")
    private boolean done;

    @JsonProperty("cumulative_reward")
    private double cumulativeReward;

    @JsonProperty("action_history")
    private List<Map<String, Object>> actionHistory;

    @JsonProperty("ground_truth")
    private GroundTruth groundTruth;

    @JsonProperty("environment_version")
    private String environmentVersion;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroundTruth {
        private String priority;
        private String category;
        private String team;
        @JsonProperty("resolution_hint")
        private String resolutionHint;
    }
}

