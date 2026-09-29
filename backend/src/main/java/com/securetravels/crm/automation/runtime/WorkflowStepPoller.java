package com.securetravels.crm.automation.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Resumes parked runs whose WAIT window has passed or whose retry backoff is
 * due (Phase 6 Module 2). Claims rows with {@code FOR UPDATE SKIP LOCKED} so
 * concurrent pollers slice the queue; each row is deleted inside its claim
 * transaction BEFORE the run is driven on. If the node dies between delete and
 * resume the run stays WAITING with no schedule row and the recovery sweep —
 * which re-enters exactly such runs — takes over, so the only cost of that
 * window is a few seconds of latency, never a missed firing.
 *
 * <p>APPROVAL rows are deliberately excluded: they are resumed only by an
 * explicit human decision via {@link WorkflowRunService#approve}.
 */
@Service
public class WorkflowStepPoller {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStepPoller.class);

    private final WorkflowScheduledStepRepository scheduled;
    private final WorkflowRunRepository runs;
    private final WorkflowRunService engine;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final com.securetravels.crm.common.config.AppProperties.Automation cfg;

    public WorkflowStepPoller(WorkflowScheduledStepRepository scheduled,
                              WorkflowRunRepository runs,
                              WorkflowRunService engine,
                              JdbcTemplate jdbc,
                              PlatformTransactionManager tm,
                              com.securetravels.crm.common.config.AppProperties props) {
        this.scheduled = scheduled;
        this.runs = runs;
        this.engine = engine;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.cfg = props.getAutomation();
    }

    @Scheduled(fixedDelayString = "${app.automation.step-poll-millis}",
               initialDelayString = "${app.automation.step-poll-millis}")
    public void drain() {
        List<WorkflowScheduledStep> due = tx.execute(s ->
                jdbc.queryForList("""
                        SELECT id FROM workflow_scheduled_steps
                         WHERE kind IN ('WAIT', 'RETRY') AND run_after <= now()
                         ORDER BY run_after
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                        """, cfg.getPollBatchSize()).stream()
                        .map(row -> {
                            UUID id = (UUID) row.get("id");
                            return scheduled.findById(id).orElse(null);
                        })
                        .filter(java.util.Objects::nonNull)
                        .toList());
        if (due == null) return;
        for (WorkflowScheduledStep row : due) {
            resume(row);
        }
    }

    private void resume(WorkflowScheduledStep row) {
        String position;
        UUID runId;
        try {
            WorkflowRun run = tx.execute(s -> {
                WorkflowRun r = runs.findById(row.getRunId()).orElse(null);
                scheduled.deleteById(row.getId());
                if (r == null || !r.alive()) {
                    return r;
                }
                r.resume();
                runs.save(r);
                return r;
            });
            if (run == null || !run.alive()) {
                log.debug("[automation] poller no-op for run {} (gone or terminal)", row.getRunId());
                return;
            }
            runId = run.getId();
            position = run.getPosition();
        } catch (Exception e) {
            log.warn("[automation] poller failed resuming run {}", row.getRunId(), e);
            return;
        }
        log.debug("[automation] poller resumes run {} from {}", runId, position);
        engine.resumeAt(runId, position);
    }
}