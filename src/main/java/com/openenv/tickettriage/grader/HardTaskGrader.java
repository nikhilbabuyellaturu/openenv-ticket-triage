package com.openenv.tickettriage.grader;

import com.openenv.tickettriage.model.Action;
import com.openenv.tickettriage.model.Reward;
import com.openenv.tickettriage.model.SupportTicket;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * HARD Task Grader: Complete IT Ticket Triage
 *
 * Objective: Full triage — priority + category + team + resolution suggestion
 *            + similar ticket references + estimated resolution hours.
 *
 * Reward weights (total = 1.0):
 *   - Priority:              0.25 (with partial credit for near-miss)
 *   - Category:              0.20 (binary)
 *   - Team:                  0.15 (binary)
 *   - Resolution suggestion: 0.25 (keyword coverage from ground truth)
 *   - Similar tickets:       0.10 (correct references from same category)
 *   - Time estimate:         0.05 (within acceptable range)
 *
 * Resolution scoring uses keyword overlap against ground truth resolution hint.
 * Time estimate is scored as inverse of normalized error from expected range.
 *
 * Multi-step: Agent gets up to 3 steps before episode ends.
 * Steps are designed so each step can refine the previous action.
 */
@Component
public class HardTaskGrader {

    private static final List<String> PRIORITY_ORDER = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> VALID_CATEGORIES = List.of("HARDWARE","SOFTWARE","NETWORK","SECURITY","ACCESS","OTHER");
    private static final List<String> VALID_TEAMS = List.of("HELPDESK","SYSADMIN","NETWORK_OPS","SECURITY_OPS","DEVOPS","MANAGEMENT");

    // Expected resolution hours by priority
    private static final Map<String, int[]> EXPECTED_HOURS = Map.of(
        "CRITICAL", new int[]{1, 4},
        "HIGH",     new int[]{4, 24},
        "MEDIUM",   new int[]{24, 72},
        "LOW",      new int[]{72, 168}
    );

    public Reward grade(Action action, SupportTicket ticket,
                        List<SupportTicket> similarTickets,
                        double cumulativeReward, boolean isFinalStep) {

        Map<String, Double> scores = new HashMap<>();
        List<String> feedback = new ArrayList<>();
        double penalty = 0.0;

        // ── Priority (0.25) ──
        String submittedPriority = normalize(action.getPriority());
        String expectedPriority = ticket.getGroundTruthPriority().name();
        double priorityScore = 0.0;
        if (!PRIORITY_ORDER.contains(submittedPriority)) {
            penalty += 0.05;
            feedback.add("Invalid/missing priority. Penalty -0.05.");
        } else {
            int diff = Math.abs(PRIORITY_ORDER.indexOf(submittedPriority) - PRIORITY_ORDER.indexOf(expectedPriority));
            priorityScore = switch (diff) {
                case 0 -> 0.25;
                case 1 -> 0.13;
                case 2 -> 0.05;
                default -> 0.0;
            };
            feedback.add(String.format("Priority '%s' %s expected '%s' (+%.2f)",
                submittedPriority, diff == 0 ? "✓" : "≈", expectedPriority, priorityScore));
        }
        scores.put("priority_score", priorityScore);

        // ── Category (0.20) ──
        String submittedCategory = normalize(action.getCategory());
        String expectedCategory = ticket.getGroundTruthCategory().name();
        double categoryScore = 0.0;
        if (!VALID_CATEGORIES.contains(submittedCategory)) {
            penalty += 0.03;
            feedback.add("Invalid/missing category. Penalty -0.03.");
        } else {
            categoryScore = submittedCategory.equals(expectedCategory) ? 0.20 : 0.0;
            feedback.add(String.format("Category '%s' %s (+%.2f)",
                submittedCategory, submittedCategory.equals(expectedCategory) ? "✓" : "✗", categoryScore));
        }
        scores.put("category_score", categoryScore);

        // ── Team (0.15) ──
        String submittedTeam = normalize(action.getAssignedTeam());
        String expectedTeam = ticket.getGroundTruthTeam().name();
        double teamScore = 0.0;
        if (!VALID_TEAMS.contains(submittedTeam)) {
            penalty += 0.03;
            feedback.add("Invalid/missing team. Penalty -0.03.");
        } else {
            teamScore = submittedTeam.equals(expectedTeam) ? 0.15 : 0.0;
            feedback.add(String.format("Team '%s' %s (+%.2f)",
                submittedTeam, submittedTeam.equals(expectedTeam) ? "✓" : "✗", teamScore));
        }
        scores.put("team_score", teamScore);

        // ── Resolution Suggestion (0.25) ──
        double resolutionScore = scoreResolution(action.getResolutionSuggestion(),
                                                 ticket.getGroundTruthResolutionHint());
        scores.put("resolution_score", resolutionScore);
        if (action.getResolutionSuggestion() == null || action.getResolutionSuggestion().isBlank()) {
            feedback.add("Resolution suggestion missing (+0.00)");
            penalty += 0.05;
        } else {
            feedback.add(String.format("Resolution suggestion keyword coverage: %.0f%% (+%.2f)",
                resolutionScore / 0.25 * 100, resolutionScore));
        }

        // ── Similar Tickets (0.10) ──
        double referenceScore = scoreReferences(action.getSimilarTicketIds(), similarTickets);
        scores.put("reference_score", referenceScore);
        feedback.add(String.format("Similar ticket references (+%.2f)", referenceScore));

        // ── Time Estimate (0.05) ──
        double timeScore = scoreTimeEstimate(action.getEstimatedResolutionHours(), expectedPriority);
        scores.put("time_score", timeScore);
        if (action.getEstimatedResolutionHours() == null) {
            feedback.add("Resolution time estimate missing (+0.00)");
        } else {
            feedback.add(String.format("Time estimate %dh (+%.2f)", action.getEstimatedResolutionHours(), timeScore));
        }

        double rawScore = priorityScore + categoryScore + teamScore + resolutionScore + referenceScore + timeScore;
        double totalReward = Math.max(0.0, rawScore - penalty);
        double newCumulative = cumulativeReward + totalReward;

        return Reward.builder()
                .value(totalReward)
                .componentScores(scores)
                .feedback(String.join(" | ", feedback))
                .penaltyApplied(penalty)
                .episodeComplete(isFinalStep)
                .cumulativeReward(newCumulative)
                .build();
    }

    /**
     * Score resolution suggestion by keyword overlap with ground truth hint.
     * Returns up to 0.25.
     */
    private double scoreResolution(String suggestion, String groundTruth) {
        if (suggestion == null || suggestion.isBlank()) return 0.0;
        if (groundTruth == null || groundTruth.isBlank()) return 0.0;

        Set<String> groundTruthWords = tokenize(groundTruth);
        Set<String> suggestionWords = tokenize(suggestion);

        long matches = suggestionWords.stream()
                .filter(groundTruthWords::contains)
                .count();

        double coverage = groundTruthWords.isEmpty() ? 0.0
                : (double) matches / groundTruthWords.size();

        return Math.min(0.25, coverage * 0.25);
    }

    /**
     * Score similar ticket references. Correct references are from the same category.
     * Returns up to 0.10.
     */
    private double scoreReferences(List<String> submitted, List<SupportTicket> similarTickets) {
        if (submitted == null || submitted.isEmpty()) return 0.0;
        if (similarTickets == null || similarTickets.isEmpty()) return 0.0;

        Set<String> validIds = new HashSet<>();
        similarTickets.forEach(t -> validIds.add(t.getTicketId()));

        long correct = submitted.stream().filter(validIds::contains).count();
        double score = (double) correct / Math.max(submitted.size(), 1);
        return Math.min(0.10, score * 0.10);
    }

    /**
     * Score time estimate against priority-based expected range.
     * Returns up to 0.05.
     */
    private double scoreTimeEstimate(Integer hours, String priority) {
        if (hours == null) return 0.0;
        int[] range = EXPECTED_HOURS.getOrDefault(priority, new int[]{24, 72});
        if (hours >= range[0] && hours <= range[1]) {
            return 0.05; // Within expected range
        }
        // Partial credit for being close
        int midpoint = (range[0] + range[1]) / 2;
        double error = Math.abs(hours - midpoint) / (double) midpoint;
        return Math.max(0.0, 0.05 * (1.0 - Math.min(error, 1.0)));
    }

    private Set<String> tokenize(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() > 3) words.add(w); // ignore short stop words
        }
        return words;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }
}
