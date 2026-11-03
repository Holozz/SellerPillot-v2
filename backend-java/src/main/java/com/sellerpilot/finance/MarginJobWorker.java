package com.sellerpilot.finance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="sellerpilot.margin.worker.enabled", havingValue="true", matchIfMissing=true)
class MarginJobWorker {
    private final MarginJobs jobs;
    private boolean recovered;

    MarginJobWorker(MarginJobs jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelay=1000)
    void tick() {
        if (!recovered) {
            jobs.recover();
            recovered = true;
        }
        jobs.runNext();
    }
}
