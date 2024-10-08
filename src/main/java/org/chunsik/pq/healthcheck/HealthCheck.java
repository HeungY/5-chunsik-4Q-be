package org.chunsik.pq.healthcheck;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/v1")
public class HealthCheck {

    @GetMapping("/health")
    public ResponseEntity<String> health(){
        return ResponseEntity.ok("server is alive");
    }
}
