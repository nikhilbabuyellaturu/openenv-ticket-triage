package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from POST /step — OpenEnv spec: observation, reward, done, info.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResponse {

    private Observation observation;
    private Reward reward;
    private boolean done;
    private StepInfo info;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StepInfo {
        @JsonProperty("episode_id")
        private String episodeId;

        @JsonProperty("step_count")
        private int stepCount;

        @JsonProperty("task_type")
        private String taskType;

        @JsonProperty("truncated")
        private boolean truncated;

        @JsonProperty("validation_errors")
        private java.util.List<String> validationErrors;
    }
}
