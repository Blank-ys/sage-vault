package com.sagevault.kb.bootstrap;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/checkAliveServer")
    public Map<String, String> checkAlive() {
        return Map.of("status", "UP");
    }
}
