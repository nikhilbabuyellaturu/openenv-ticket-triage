package com.openenv.tickettriage.baseline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;

/**
 * Baseline Inference Runner.
 *
 * Runs an LLM agent (via OpenAI API) against all 3 task types using
 * the environment's HTTP API. Produces reproducible baseline scores.
 *
 * Usage: POST /api/v1/baseline/run?task_type=ALL
 *
 * Reads OPENAI_API_KEY from environment variables.
 * Uses a fixed seed for reproducibility.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BaselineRunner {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    @Value("${openai.model:gpt-4o-mini}")
    private String openAiModel;

    @Value("${server.port:8080}")
    private int serverPort;

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final long FIXED_SEED = 42L;

    /**
     * Run baseline agent against specified task types.
     * Returns per-task scores and aggregate stats.
     */
    public Map<String, Object> runBaseline(List<String> taskTypes) {
        List<Map<String, Object>> results = new ArrayList<>();
        double totalScore = 0.0;

        WebClient envClient = webClientBuilder
                .baseUrl("http://localhost:" + serverPort)
                .build();

        for (String taskType : taskTypes) {
            log.info("Running baseline for task type: {}", taskType);
            Map<String, Object> taskResult = runSingleTask(envClient, taskType);
            results.add(taskResult);
            totalScore += (double) taskResult.getOrDefault("score", 0.0);
        }

        double averageScore = taskTypes.isEmpty() ? 0.0 : totalScore / taskTypes.size();

        return Map.of(
            "baseline_model", openAiModel,
            "seed", FIXED_SEED,
            "task_results", results,
            "average_score", Math.round(averageScore * 1000.0) / 1000.0,
            "total_tasks_run", results.size(),
            "timestamp", java.time.Instant.now().toString()
        );
    }

    private Map<String, Object> runSingleTask(WebClient envClient, String taskType) {
        try {
            // 1. Reset environment
            ObjectNode resetRequest = objectMapper.createObjectNode();
            resetRequest.put("task_type", taskType);
            resetRequest.put("seed", FIXED_SEED);

            String resetResponse = envClient.post()
                    .uri("/api/v1/reset")
                    .header("Content-Type", "application/json")
                    .bodyValue(resetRequest.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode observation = objectMapper.readTree(resetResponse);
            int maxSteps = observation.path("max_steps").asInt(1);
            double cumulativeReward = 0.0;
            String lastFeedback = "";

            // 2. Agent loop
            for (int step = 0; step < maxSteps; step++) {
                // Build prompt for LLM
                String prompt = buildPrompt(observation, taskType, step);

                // Call OpenAI API
                String actionJson = callOpenAI(prompt);

                // Submit action to environment
                String stepResponse = envClient.post()
                        .uri("/api/v1/step")
                        .header("Content-Type", "application/json")
                        .bodyValue(actionJson)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block();

                JsonNode stepResult = objectMapper.readTree(stepResponse);
                cumulativeReward = stepResult.path("reward").path("cumulative_reward").asDouble();
                lastFeedback = stepResult.path("reward").path("feedback").asText();
                observation = stepResult.path("observation");

                boolean done = stepResult.path("done").asBoolean();
                log.info("Task {} | Step {} | Reward: {:.3f} | Done: {}",
                    taskType, step + 1, stepResult.path("reward").path("value").asDouble(), done);

                if (done) break;
            }

            return Map.of(
                "task_type", taskType,
                "score", Math.round(cumulativeReward * 1000.0) / 1000.0,
                "feedback", lastFeedback,
                "status", "completed"
            );

        } catch (Exception e) {
            log.error("Baseline run failed for task {}: {}", taskType, e.getMessage());
            return Map.of(
                "task_type", taskType,
                "score", 0.0,
                "error", e.getMessage(),
                "status", "failed"
            );
        }
    }

    private String buildPrompt(JsonNode observation, String taskType, int step) {
        String ticketTitle = observation.path("ticket_title").asText();
        String ticketDesc = observation.path("ticket_description").asText();
        String userName = observation.path("user_name").asText();
        String userDept = observation.path("user_department").asText();
        String taskDesc = observation.path("task_description").asText();
        JsonNode schema = observation.path("action_schema");

        StringBuilder sb = new StringBuilder();
        sb.append("You are an expert IT support triage agent. Analyze the support ticket below and respond with a valid JSON action.\n\n");
        sb.append("=== TICKET ===\n");
        sb.append("Title: ").append(ticketTitle).append("\n");
        sb.append("Description: ").append(ticketDesc).append("\n");
        sb.append("From: ").append(userName).append(" (").append(userDept).append(")\n\n");
        sb.append("=== TASK ===\n").append(taskDesc).append("\n\n");
        sb.append("=== VALID VALUES ===\n");
        sb.append("Priorities: ").append(schema.path("valid_priorities")).append("\n");
        sb.append("Categories: ").append(schema.path("valid_categories")).append("\n");
        sb.append("Teams: ").append(schema.path("valid_teams")).append("\n\n");
        sb.append("=== REQUIRED FIELDS ===\n");
        sb.append(schema.path("required_fields")).append("\n\n");

        if (taskType.equals("HARD") && observation.has("similar_tickets")) {
            sb.append("=== SIMILAR RESOLVED TICKETS ===\n");
            observation.path("similar_tickets").forEach(t -> {
                sb.append("- ").append(t.path("ticket_id").asText())
                  .append(": ").append(t.path("title").asText())
                  .append(" → ").append(t.path("resolution_summary").asText()).append("\n");
            });
            sb.append("\n");
        }

        sb.append("Respond ONLY with a valid JSON object matching this structure:\n");
        sb.append("{\n  \"priority\": \"HIGH\",\n  \"category\": \"SOFTWARE\",\n");
        sb.append("  \"assigned_team\": \"SYSADMIN\",\n  \"resolution_suggestion\": \"...\",\n");
        sb.append("  \"similar_ticket_ids\": [\"TKT-XXX\"],\n");
        sb.append("  \"estimated_resolution_hours\": 4,\n  \"reasoning\": \"...\"\n}\n");
        sb.append("Only include fields required for the current task type.");

        return sb.toString();
    }

    private String callOpenAI(String prompt) throws Exception {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            log.warn("OPENAI_API_KEY not set — returning mock action for baseline test");
            return getMockAction();
        }

        WebClient openAiClient = webClientBuilder
                .baseUrl(OPENAI_URL)
                .defaultHeader("Authorization", "Bearer " + openAiApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", openAiModel);
        requestBody.put("max_tokens", 512);
        requestBody.put("temperature", 0.2);

        var messages = objectMapper.createArrayNode();
        var userMsg = objectMapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);
        messages.add(userMsg);
        requestBody.set("messages", messages);

        String response = openAiClient.post()
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode responseNode = objectMapper.readTree(response);
        String content = responseNode
                .path("choices").get(0)
                .path("message").path("content").asText();

        // Clean up markdown code fences if present
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        log.debug("OpenAI response: {}", content);
        return content;
    }

    /**
     * Mock action for testing without OpenAI API key.
     * Simulates a medium-quality agent for baseline validation.
     */
    private String getMockAction() {
        return """
            {
              "priority": "HIGH",
              "category": "SOFTWARE",
              "assigned_team": "SYSADMIN",
              "resolution_suggestion": "Check system logs, restart the affected service, update driver or patch if needed.",
              "similar_ticket_ids": ["TKT-004"],
              "estimated_resolution_hours": 8,
              "reasoning": "Mock baseline agent response for testing without OpenAI API key."
            }
            """;
    }
}
