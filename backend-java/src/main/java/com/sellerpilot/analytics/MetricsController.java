package com.sellerpilot.analytics;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/stores/{sid}/metrics")
public class MetricsController {
    private final MetricsService service;
    public MetricsController(MetricsService service){this.service=service;}
    @PostMapping("/query")public Map<String,Object> query(@AuthenticationPrincipal Long user,@PathVariable long sid,@RequestBody QuerySpec query){return service.query(user,sid,query);}
}
