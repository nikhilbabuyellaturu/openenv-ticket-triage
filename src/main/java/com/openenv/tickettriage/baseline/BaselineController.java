package com.openenv.tickettriage.baseline;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Baseline inference endpoint.
 * Triggers an LLM agent run across specified task types and returns scores.
 */
@RestController
@RequestMapping("/api/v1/baseline")
@RequiredArgsConstructor
@Tag(name = "Baseline Runner", description = "Run LLM baseline agent against the environment")
public class BaselineController {

    private final BaselineRunner baselineRunner;

    @PostMapping("/run")
    @Operation(summary = "Run baseline inference",
               description = "Runs the LLM agent against EASY, MEDIUM, HARD tasks and returns baseline scores.")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam(defaultValue = "EASY,MEDIUM,HARD") String taskTypes) {

        List<String> tasks = List.of(taskTypes.split(","));
        Map<String, Object> results = baselineRunner.runBaseline(tasks);
        return ResponseEntity.ok(results);
    }
}
