package com.securetravels.crm.automation.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The safety net (Phase 6 Module 2). Re-enters live runs that appear stuck:
 * a coordinator killed mid-loop, a node that died between deleting a WAIT
 * schedule and resuming the run, a run created but whose first step never
 * executed. A live run is only recovered when no schedule row still owns it
 * (a parked run with an unexpired schedule is not stuck — it is waiting on
 * purpose) and after a grace period so a merely slow step is not raced. The
 * run itself is idempotent to re-entry, so the cost of a false positive is
 * exactly zero work, never a double effect.
 */
@Service
public class RunRecoverySweep {

    private static final Logger log = LoggerFactory.getLogger(RunRecoverySweep.class);

    private final WorkflowRunRepository runs;
    private final WorkflowScheduledStepRepository scheduled;
    private final WorkflowRunService engine;
    private final com.securetravels.crm.common.config.AppProperties.Automation cfg;

    public RunRecoverySweep(WorkflowRunRepository runs,
                            WorkflowScheduledStepRepository scheduled,
                            WorkflowRunService engine,
                            com.securetravels.crm.common.config.AppProperties props) {
        this.runs = runs;
        this.scheduled = scheduled;
        this.engine = engine;
        this.cfg = props.getAutomation();
    }

    @Scheduled(fixedDelayString = "${app.automation.recovery-interval-millis}",
               initialDelayString = "${app.automation.recovery-interval-millis}")
    public void runOnce() {
        Instant staleAfter = Instant.now().minus(Duration.ofMillis(cfg.getRecoveryGraceMillis()));
        for (WorkflowRun run : runs.findAllByStatusIn(Set.of(
                WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING))) {
            if (run.getUpdatedAt() == null || !run.getUpdatedAt().isBefore(staleAfter)) {
                continue;
            }
            if (!scheduled.findByRunId(run.getId()).isEmpty()) {
                continue;                       // parked on purpose; not stuck
            }
            String position = run.getPosition();
            if (position == null) {
                position = engine.firstStepId(run.getId());
            }
            if (position == null) {
                continue;
            }
            log.info("[automation] recovering stale run {} from {}", run.getId(), position);
            engine.resumeAt(run.getId(), position);
        }
    }
}