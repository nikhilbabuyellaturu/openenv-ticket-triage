package com.openenv.tickettriage.grader;

import com.openenv.tickettriage.model.Action;
import com.openenv.tickettriage.model.Reward;
import com.openenv.tickettriage.model.SupportTicket;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EASY Task Grader: Priority Classification
 *
 * Objective: Given a support ticket, classify its priority (LOW/MEDIUM/HIGH/CRITICAL).
 * Score range: 0.0 – 1.0
 *
 * Reward breakdown:
 *   - Exact match:              1.0
 *   - One level off:            0.5
 *   - Two levels off:           0.2
 *   - Three+ levels off:        0.0
 *   - Invalid priority value:  -0.1 penalty
 *
 * This grader provides partial credit for near-correct answers, giving
 * meaningful reward signal rather than binary success/failure.
 */
@Component
public class EasyTaskGrader {

    private static final List<String> PRIORITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    public Reward grade(Action action, SupportTicket ticket, double cumulativeReward) {
        Map<String, Double> componentScores = new HashMap<>();
        List<String> validationErrors = new ArrayList<>();
        double penalty = 0.0;
        String feedback;

        String submitted = action.getPriority() == null ? "" : action.getPriority().trim().toUpperCase();
        String expected = ticket.getGroundTruthPriority().name();

        // Validate the submitted priority
        if (!PRIORITY_ORDER.contains(submitted)) {
            penalty = 0.1;
            componentScores.put("priority_score", 0.0);
            feedback = String.format(
                "Invalid priority value: '%s'. Valid values are: %s. Penalty applied: -%.1f",
                action.getPriority(), PRIORITY_ORDER, penalty
            );
            return buildReward(0.0, componentScores, feedback, penalty, cumulativeReward, true);
        }

        double priorityScore = computePriorityScore(submitted, expected);
        componentScores.put("priority_score", priorityScore);

        if (priorityScore == 1.0) {
            feedback = String.format("Correct! Priority '%s' is accurate for this ticket.", submitted);
        } else if (priorityScore >= 0.5) {
            feedback = String.format(
                "Close. You classified '%s' but the correct priority is '%s' (one level off).",
                submitted, expected
            );
        } else if (priorityScore > 0.0) {
            feedback = String.format(
                "Partially off. You classified '%s' but the correct priority is '%s' (two levels off).",
                submitted, expected
            );
        } else {
            feedback = String.format(
                "Incorrect. You classified '%s' but the correct priority is '%s'.",
                submitted, expected
            );
        }

        double totalReward = Math.max(0.0, priorityScore - penalty);
        return buildReward(totalReward, componentScores, feedback, penalty, cumulativeReward + totalReward, true);
    }

    private double computePriorityScore(String submitted, String expected) {
        int submittedIdx = PRIORITY_ORDER.indexOf(submitted);
        int expectedIdx = PRIORITY_ORDER.indexOf(expected);
        int diff = Math.abs(submittedIdx - expectedIdx);
        return switch (diff) {
            case 0 -> 1.0;
            case 1 -> 0.5;
            case 2 -> 0.2;
            default -> 0.0;
        };
    }

    private Reward buildReward(double value, Map<String, Double> components,
                                String feedback, double penalty,
                                double cumulative, boolean done) {
        return Reward.builder()
                .value(value)
                .componentScores(components)
                .feedback(feedback)
                .penaltyApplied(penalty)
                .episodeComplete(done)
                .cumulativeReward(cumulative)
                .build();
    }
}
