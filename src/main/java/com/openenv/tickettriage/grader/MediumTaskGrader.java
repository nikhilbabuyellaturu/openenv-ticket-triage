package com.openenv.tickettriage.grader;

import com.openenv.tickettriage.model.Action;
import com.openenv.tickettriage.model.Reward;
import com.openenv.tickettriage.model.SupportTicket;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MEDIUM Task Grader: Full Categorization
 *
 * Objective: Classify priority + category + assigned team.
 *
 * Reward weights (total = 1.0):
 *   - Priority score:  0.40 (exact=0.40, ±1=0.20, ±2=0.08, ±3=0.00)
 *   - Category score:  0.35 (exact=0.35, wrong=0.00)
 *   - Team score:      0.25 (exact=0.25, wrong=0.00)
 *
 * Partial credit: Missing optional fields get 0 for that component.
 * Penalty: -0.05 for each missing required field.
 */
@Component
public class MediumTaskGrader {

    private static final List<String> PRIORITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> VALID_CATEGORIES = List.of("HARDWARE","SOFTWARE","NETWORK","SECURITY","ACCESS","OTHER");
    private static final List<String> VALID_TEAMS = List.of("HELPDESK","SYSADMIN","NETWORK_OPS","SECURITY_OPS","DEVOPS","MANAGEMENT");

    private static final double WEIGHT_PRIORITY = 0.40;
    private static final double WEIGHT_CATEGORY = 0.35;
    private static final double WEIGHT_TEAM = 0.25;

    public Reward grade(Action action, SupportTicket ticket, double cumulativeReward) {
        Map<String, Double> scores = new HashMap<>();
        List<String> feedback = new ArrayList<>();
        double penalty = 0.0;

        // ── Priority ──
        String submittedPriority = normalize(action.getPriority());
        String expectedPriority = ticket.getGroundTruthPriority().name();
        double priorityScore;
        if (submittedPriority.isEmpty()) {
            priorityScore = 0.0;
            penalty += 0.05;
            feedback.add("Priority missing (required field). Penalty -0.05.");
        } else if (!PRIORITY_ORDER.contains(submittedPriority)) {
            priorityScore = 0.0;
            penalty += 0.05;
            feedback.add(String.format("Invalid priority '%s'. Penalty -0.05.", action.getPriority()));
        } else {
            int diff = Math.abs(PRIORITY_ORDER.indexOf(submittedPriority) - PRIORITY_ORDER.indexOf(expectedPriority));
            priorityScore = switch (diff) {
                case 0 -> WEIGHT_PRIORITY;
                case 1 -> WEIGHT_PRIORITY * 0.5;
                case 2 -> WEIGHT_PRIORITY * 0.2;
                default -> 0.0;
            };
            if (diff == 0) feedback.add(String.format("Priority '%s' ✓ (+%.2f)", submittedPriority, priorityScore));
            else feedback.add(String.format("Priority '%s' off by %d level(s), expected '%s' (+%.2f)", submittedPriority, diff, expectedPriority, priorityScore));
        }
        scores.put("priority_score", priorityScore);

        // ── Category ──
        String submittedCategory = normalize(action.getCategory());
        String expectedCategory = ticket.getGroundTruthCategory().name();
        double categoryScore;
        if (submittedCategory.isEmpty()) {
            categoryScore = 0.0;
            penalty += 0.05;
            feedback.add("Category missing (required field). Penalty -0.05.");
        } else if (!VALID_CATEGORIES.contains(submittedCategory)) {
            categoryScore = 0.0;
            penalty += 0.03;
            feedback.add(String.format("Invalid category '%s'. Penalty -0.03.", action.getCategory()));
        } else if (submittedCategory.equals(expectedCategory)) {
            categoryScore = WEIGHT_CATEGORY;
            feedback.add(String.format("Category '%s' ✓ (+%.2f)", submittedCategory, categoryScore));
        } else {
            categoryScore = 0.0;
            feedback.add(String.format("Category '%s' incorrect, expected '%s' (+0.00)", submittedCategory, expectedCategory));
        }
        scores.put("category_score", categoryScore);

        // ── Team ──
        String submittedTeam = normalize(action.getAssignedTeam());
        String expectedTeam = ticket.getGroundTruthTeam().name();
        double teamScore;
        if (submittedTeam.isEmpty()) {
            teamScore = 0.0;
            penalty += 0.05;
            feedback.add("Assigned team missing (required field). Penalty -0.05.");
        } else if (!VALID_TEAMS.contains(submittedTeam)) {
            teamScore = 0.0;
            penalty += 0.03;
            feedback.add(String.format("Invalid team '%s'. Penalty -0.03.", action.getAssignedTeam()));
        } else if (submittedTeam.equals(expectedTeam)) {
            teamScore = WEIGHT_TEAM;
            feedback.add(String.format("Team '%s' ✓ (+%.2f)", submittedTeam, teamScore));
        } else {
            teamScore = 0.0;
            feedback.add(String.format("Team '%s' incorrect, expected '%s' (+0.00)", submittedTeam, expectedTeam));
        }
        scores.put("team_score", teamScore);

        double rawScore = priorityScore + categoryScore + teamScore;
        double totalReward = Math.max(0.0, rawScore - penalty);
        double newCumulative = cumulativeReward + totalReward;

        return Reward.builder()
                .value(totalReward)
                .componentScores(scores)
                .feedback(String.join(" | ", feedback))
                .penaltyApplied(penalty)
                .episodeComplete(true)
                .cumulativeReward(newCumulative)
                .build();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
