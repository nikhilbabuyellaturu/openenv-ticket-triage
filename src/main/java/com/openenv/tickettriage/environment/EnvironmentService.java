package com.openenv.tickettriage.environment;

import com.openenv.tickettriage.grader.EasyTaskGrader;
import com.openenv.tickettriage.grader.HardTaskGrader;
import com.openenv.tickettriage.grader.MediumTaskGrader;
import com.openenv.tickettriage.model.*;
import com.openenv.tickettriage.model.Observation.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Core OpenEnv environment service.
 *
 * Implements the OpenEnv spec:
 *   reset(ResetRequest) → Observation   (initial observation)
 *   step(Action)        → StepResponse  (observation, reward, done, info)
 *   state()             → EnvironmentState (full state with ground truth for eval)
 *
 * NOTE: This service holds a single EpisodeState (one active episode at a time).
 * It is synchronized for thread safety within a single JVM.
 * For multi-agent use, replace EpisodeState with a session-keyed store.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnvironmentService {

    private final TicketDataStore  ticketDataStore;
    private final EasyTaskGrader   easyGrader;
    private final MediumTaskGrader mediumGrader;
    private final HardTaskGrader   hardGrader;

    private static final Map<TaskType, Integer> MAX_STEPS = Map.of(
        TaskType.EASY,   1,
        TaskType.MEDIUM, 1,
        TaskType.HARD,   3
    );

    // Current active episode — replaced on every reset()
    private EpisodeState episode;

    // ─────────────────────────────────────────────────────────────
    // OpenEnv: reset()
    // ─────────────────────────────────────────────────────────────

    public synchronized Observation reset(ResetRequest request) {
        TaskType taskType = parseTaskType(request.getTaskType());
        int maxSteps = MAX_STEPS.getOrDefault(taskType, 1);

        SupportTicket ticket = resolveTicket(request);

        this.episode = new EpisodeState(taskType, ticket, maxSteps, request.getSeed());

        log.info("Episode {} started | Task={} | Ticket={}",
                 episode.getEpisodeId(), taskType, ticket.getTicketId());

        return buildObservation();
    }

    // ─────────────────────────────────────────────────────────────
    // OpenEnv: step(action)
    // ─────────────────────────────────────────────────────────────

    public synchronized StepResponse step(Action action) {
        if (episode == null) {
            throw new IllegalStateException(
                "No active episode. Call POST /api/v1/reset before stepping.");
        }
        if (episode.isDone()) {
            return buildNoopResponse("Episode is already done. Call /reset to start a new episode.");
        }

        episode.incrementStep();

        // Record action in history
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("step",                        episode.getStepCount());
        record.put("priority",                    action.getPriority());
        record.put("category",                    action.getCategory());
        record.put("assigned_team",               action.getAssignedTeam());
        record.put("resolution_suggestion",       action.getResolutionSuggestion());
        record.put("similar_ticket_ids",          action.getSimilarTicketIds());
        record.put("estimated_resolution_hours",  action.getEstimatedResolutionHours());
        record.put("timestamp",                   Instant.now().toString());
        episode.recordAction(record);

        // Grade action
        Reward reward = gradeAction(action);

        episode.setCumulativeReward(reward.getCumulativeReward());

        // End episode on final step or when grader signals completion
        if (episode.isFinalStep() || reward.isEpisodeComplete()) {
            episode.setDone(true);
        }

        log.info("Episode {} | Step {}/{} | Reward={} | Done={}",
                 episode.getEpisodeId(), episode.getStepCount(), episode.getMaxSteps(),
                 String.format("%.3f", reward.getValue()), episode.isDone());

        return StepResponse.builder()
                .observation(buildObservation())
                .reward(reward)
                .done(episode.isDone())
                .info(StepResponse.StepInfo.builder()
                        .episodeId(episode.getEpisodeId())
                        .stepCount(episode.getStepCount())
                        .taskType(episode.getTaskType().name())
                        .truncated(episode.isFinalStep() && !reward.isEpisodeComplete())
                        .validationErrors(List.of())
                        .build())
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // OpenEnv: state()
    // ─────────────────────────────────────────────────────────────

    public synchronized EnvironmentState state() {
        if (episode == null) {
            return EnvironmentState.builder()
                    .episodeId("NOT_STARTED")
                    .done(true)
                    .environmentVersion("1.0.0")
                    .build();
        }

        SupportTicket t = episode.getTicket();
        EnvironmentState.GroundTruth gt = EnvironmentState.GroundTruth.builder()
                .priority(t.getGroundTruthPriority().name())
                .category(t.getGroundTruthCategory().name())
                .team(t.getGroundTruthTeam().name())
                .resolutionHint(t.getGroundTruthResolutionHint())
                .build();

        return EnvironmentState.builder()
                .episodeId(episode.getEpisodeId())
                .taskType(episode.getTaskType())
                .currentTicketId(t.getTicketId())
                .stepCount(episode.getStepCount())
                .maxSteps(episode.getMaxSteps())
                .done(episode.isDone())
                .cumulativeReward(episode.getCumulativeReward())
                .actionHistory(episode.getActionHistory())
                .groundTruth(gt)
                .environmentVersion("1.0.0")
                .build();
    }

    // ─────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────

    private Reward gradeAction(Action action) {
        return switch (episode.getTaskType()) {
            case EASY   -> easyGrader.grade(action, episode.getTicket(), episode.getCumulativeReward());
            case MEDIUM -> mediumGrader.grade(action, episode.getTicket(), episode.getCumulativeReward());
            case HARD   -> {
                List<SupportTicket> similar = ticketDataStore.findSimilar(
                    episode.getTicket().getTicketId(), 5);
                yield hardGrader.grade(action, episode.getTicket(), similar,
                                       episode.getCumulativeReward(), episode.isFinalStep());
            }
        };
    }

    private Observation buildObservation() {
        List<Observation.SimilarTicketSummary> similar = new ArrayList<>();
        if (episode.getTaskType() == TaskType.HARD) {
            ticketDataStore.findSimilar(episode.getTicket().getTicketId(), 3).forEach(t ->
                similar.add(Observation.SimilarTicketSummary.builder()
                        .ticketId(t.getTicketId())
                        .title(t.getTitle())
                        .resolutionSummary(t.getGroundTruthResolutionHint())
                        .resolvedInHours(episode.getRng().nextInt(48) + 1)
                        .build()));
        }

        List<String> requiredFields = new ArrayList<>(List.of("priority"));
        List<String> optionalFields = new ArrayList<>(List.of("reasoning"));
        if (episode.getTaskType() == TaskType.MEDIUM || episode.getTaskType() == TaskType.HARD) {
            requiredFields.addAll(List.of("category", "assigned_team"));
        }
        if (episode.getTaskType() == TaskType.HARD) {
            requiredFields.addAll(List.of("resolution_suggestion",
                                          "similar_ticket_ids",
                                          "estimated_resolution_hours"));
        }

        Observation.ActionSchema schema = Observation.ActionSchema.builder()
                .requiredFields(requiredFields)
                .optionalFields(optionalFields)
                .validPriorities(List.of("LOW", "MEDIUM", "HIGH", "CRITICAL"))
                .validCategories(List.of("HARDWARE","SOFTWARE","NETWORK","SECURITY","ACCESS","OTHER"))
                .validTeams(List.of("HELPDESK","SYSADMIN","NETWORK_OPS","SECURITY_OPS","DEVOPS","MANAGEMENT"))
                .build();

        SupportTicket ticket = episode.getTicket();

        return Observation.builder()
                .ticketId(ticket.getTicketId())
                .ticketTitle(ticket.getTitle())
                .ticketDescription(ticket.getDescription())
                .userName(ticket.getUserName())
                .userDepartment(ticket.getUserDepartment())
                .submittedAt(ticket.getSubmittedAt())
                .taskType(episode.getTaskType())
                .taskDescription(taskDescription(episode.getTaskType()))
                .stepCount(episode.getStepCount())
                .maxSteps(episode.getMaxSteps())
                .done(episode.isDone())
                .previousActions(episode.getActionHistory())
                .cumulativeReward(episode.getCumulativeReward())
                .actionSchema(schema)
                .similarTickets(similar)
                .build();
    }

    private String taskDescription(TaskType taskType) {
        return switch (taskType) {
            case EASY   -> "Classify the priority of this support ticket. Valid values: LOW, MEDIUM, HIGH, CRITICAL.";
            case MEDIUM -> "Classify the priority, category, and assign the ticket to the correct support team.";
            case HARD   -> "Full triage: classify priority, category, assign team, write a resolution suggestion, reference similar tickets, and estimate resolution time in hours.";
        };
    }

    private TaskType parseTaskType(String raw) {
        if (raw == null) return TaskType.EASY;
        try {
            return TaskType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown task_type '{}', defaulting to EASY", raw);
            return TaskType.EASY;
        }
    }

    private SupportTicket resolveTicket(ResetRequest request) {
        Random rng = request.getSeed() != null ? new Random(request.getSeed()) : new Random();
        if (request.getTicketId() != null) {
            return ticketDataStore.findById(request.getTicketId())
                    .orElseGet(() -> {
                        log.warn("Ticket '{}' not found, using random.", request.getTicketId());
                        return ticketDataStore.getRandomTicket(rng);
                    });
        }
        return ticketDataStore.getRandomTicket(rng);
    }

    private StepResponse buildNoopResponse(String message) {
        return StepResponse.builder()
                .observation(buildObservation())
                .reward(Reward.builder()
                        .value(0.0)
                        .componentScores(Map.of())
                        .feedback(message)
                        .penaltyApplied(0.0)
                        .episodeComplete(true)
                        .cumulativeReward(episode.getCumulativeReward())
                        .build())
                .done(true)
                .info(StepResponse.StepInfo.builder()
                        .episodeId(episode.getEpisodeId())
                        .stepCount(episode.getStepCount())
                        .taskType(episode.getTaskType().name())
                        .truncated(false)
                        .validationErrors(List.of(message))
                        .build())
                .build();
    }
}
