package com.openenv.tickettriage.environment;

import com.openenv.tickettriage.model.Observation.TaskType;
import com.openenv.tickettriage.model.SupportTicket;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Mutable value object holding all state for a single episode.
 * Extracted from EnvironmentService to make state boundaries explicit.
 *
 * NOTE: EnvironmentService is a single-instance Spring singleton.
 * State here is per-JVM — suitable for single-agent evaluation.
 * For multi-agent / multi-tenant use, replace with a Redis-backed
 * session store keyed by episodeId.
 */
@Data
public class EpisodeState {

    private final String episodeId;
    private final TaskType taskType;
    private final SupportTicket ticket;
    private final int maxSteps;
    private final Random rng;

    private int stepCount = 0;
    private double cumulativeReward = 0.0;
    private boolean done = false;
    private final List<Map<String, Object>> actionHistory = new ArrayList<>();

    public EpisodeState(TaskType taskType, SupportTicket ticket, int maxSteps, Long seed) {
        this.episodeId = "EP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        this.taskType  = taskType;
        this.ticket    = ticket;
        this.maxSteps  = maxSteps;
        this.rng       = seed != null ? new Random(seed) : new Random();
    }

    public void incrementStep() {
        this.stepCount++;
    }

    public boolean isFinalStep() {
        return stepCount >= maxSteps;
    }

    public void recordAction(Map<String, Object> actionRecord) {
        actionHistory.add(actionRecord);
    }
}
