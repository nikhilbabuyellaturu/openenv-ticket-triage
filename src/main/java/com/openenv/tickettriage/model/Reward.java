package com.openenv.tickettriage.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * OpenEnv Reward model.
 * Provides dense, multi-dimensional reward signals — NOT just binary end-of-episode.
 * Value is always in [0.0, 1.0].
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Reward {

    /** Overall scalar reward for this step [0.0, 1.0] */
    private double value;

    /**
     * Per-component breakdown:
     * - priority_score       [0.0, 0.4]: correct priority classification
     * - category_score       [0.0, 0.25]: correct category classification
     * - team_score           [0.0, 0.15]: correct team assignment
     * - resolution_score     [0.0, 0.15]: quality of resolution suggestion
     * - reference_score      [0.0, 0.05]: correct similar ticket references
     * Weights vary by task type (EASY/MEDIUM/HARD)
     */
    @JsonProperty("component_scores")
    private Map<String, Double> componentScores;

    /** Human-readable feedback for the agent */
    private String feedback;

    /** Whether this step completed the episode */
    @JsonProperty("episode_complete")
    private boolean episodeComplete;

    /** Penalty applied (e.g. for invalid action, missing required fields) */
    @JsonProperty("penalty_applied")
    private double penaltyApplied;

    /** Cumulative reward so far in this episode */
    @JsonProperty("cumulative_reward")
    private double cumulativeReward;
}
