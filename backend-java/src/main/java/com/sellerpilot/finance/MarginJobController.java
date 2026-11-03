package com.sellerpilot.finance;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/margin-report-jobs")
class MarginJobController {
    private final MarginJobs jobs;

    MarginJobController(MarginJobs jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> submit(@AuthenticationPrincipal Long user, @Valid @RequestBody MarginController.Query query) {
        return jobs.submit(user, query);
    }

    @GetMapping("/{id}")
    Map<String,Object> get(@AuthenticationPrincipal Long user, @PathVariable String id) {
        return jobs.get(user, id);
    }

    @PostMapping("/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String,Object> retry(@AuthenticationPrincipal Long user, @PathVariable String id) {
        return jobs.retry(user, id);
    }
}
