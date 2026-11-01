package com.lonelight.contentplanner.controller;

import com.lonelight.contentplanner.common.web.Result;
import com.lonelight.contentplanner.config.AgentProperties;
import com.lonelight.contentplanner.model.dto.PlanRequest;
import com.lonelight.contentplanner.model.vo.PlanResultVO;
import com.lonelight.contentplanner.service.PlanService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PlanController {
    private final PlanService service;
    private final AgentProperties properties;

    @GetMapping("/health")
    public Result<?> health() {
        return Result.success(
                Map.of(
                        "status",
                        "UP",
                        "modelMode",
                        properties.getModelMode(),
                        "simulationEnabled",
                        properties.isSimulationEnabled()));
    }

    @PostMapping("/plans")
    public ResponseEntity<Result<PlanResultVO>> submit(@Valid @RequestBody PlanRequest request) {
        return ok(service.submit(request));
    }

    @GetMapping("/plans/{requestId}")
    public ResponseEntity<Result<PlanResultVO>> get(@PathVariable String requestId) {
        return ok(service.get(requestId));
    }

    @PostMapping("/plans/{requestId}/resume")
    public ResponseEntity<Result<PlanResultVO>> resume(@PathVariable String requestId) {
        return ok(service.resume(requestId));
    }

    @GetMapping(value = "/plans/{requestId}/trace", produces = "application/x-ndjson;charset=UTF-8")
    public ResponseEntity<String> trace(@PathVariable String requestId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(service.trace(requestId));
    }

    private ResponseEntity<Result<PlanResultVO>> ok(PlanResultVO result) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.success(result));
    }
}
