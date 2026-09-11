package com.securetravels.crm.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping(path = "/health", produces = "application/json")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "securetravels-crm",
                "time", Instant.now().toString());
    }
}