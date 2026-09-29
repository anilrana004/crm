package com.securetravels.crm.automation;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.automation.domain.WorkflowStatus;
import com.securetravels.crm.automation.domain.WorkflowVersion;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module 1 lifecycle: the draft → active → paused ⇄ active → archived state
 * machine, the single-ACTIVE invariant (editing a live workflow opens a new
 * DRAFT; publishing it pauses the previous version), and the refusal of
 * invalid definitions at the persistence boundary.
 */
class WorkflowLifecycleIT extends BaseIT {

    @Autowired private WorkflowService workflows;

    @Test
    void fullLifecyclePublishesPausesResumesAndArchives() {
        UUID actor = createUser("ops@securetravels.in", "Ops", Role.MANAGER, "P@ssw0rd");
        UUID workflowId = workflows.createWorkflow("speed-to-lead", "Speed to lead",
                "Respond within the hour", actor).getId();

        WorkflowVersion v1 = workflows.saveDraft(workflowId, WorkflowFixtures.validDefinition(), actor);
        assertThat(v1.getVersionNumber()).isEqualTo(1);
        assertThat(v1.getStatus()).isEqualTo(WorkflowStatus.DRAFT);
        assertThat(workflows.history(workflowId)).hasSize(1);

        workflows.activate(workflowId, actor);
        assertThat(workflows.activeDefinitions()).hasSize(1);
        assertThat(workflows.activeDefinitions().get(0).getVersionNumber()).isEqualTo(1);
        assertThat(workflows.activeVersion(workflowId)).isPresent();

        // Editing a live workflow opens v2 as a fresh DRAFT.
        WorkflowVersion v2 = workflows.saveDraft(workflowId, WorkflowFixtures.validDefinition(), actor);
        assertThat(v2.getVersionNumber()).isEqualTo(2);
        assertThat(workflows.history(workflowId)).hasSize(2);

        // Publishing v2 pauses v1: still exactly one ACTIVE.
        workflows.activate(workflowId, actor);
        assertThat(workflows.activeDefinitions()).hasSize(1);
        assertThat(workflows.activeDefinitions().get(0).getVersionNumber()).isEqualTo(2);
        assertThat(workflows.history(workflowId))
                .extracting(WorkflowVersion::getStatus)
                .containsExactlyInAnyOrder(WorkflowStatus.ACTIVE, WorkflowStatus.PAUSED);

        workflows.pause(workflowId, actor);
        assertThat(workflows.activeDefinitions()).isEmpty();

        workflows.resume(workflowId, actor);
        assertThat(workflows.activeDefinitions()).hasSize(1);

        workflows.archive(workflowId, actor);
        assertThat(workflows.activeDefinitions()).isEmpty();
        assertThat(workflows.history(workflowId))
                .allMatch(v -> v.getStatus() == WorkflowStatus.ARCHIVED);
    }

    @Test
    void invalidDefinitionIsRefusedBeforeAnythingIsPersisted() {
        UUID actor = createUser("ops@securetravels.in", "Ops", Role.MANAGER, "P@ssw0rd");
        UUID workflowId = workflows.createWorkflow("broken", "Broken", null, actor).getId();

        assertThatThrownBy(() -> workflows.saveDraft(workflowId,
                WorkflowFixtures.conditionGate(new com.securetravels.crm.automation.definition.Condition(
                        "T(java.lang.Runtime).getRuntime()",
                        com.securetravels.crm.automation.definition.ConditionOp.EQ, "x")),
                actor))
                .isInstanceOf(InvalidWorkflowDefinitionException.class)
                .satisfies(e -> assertThat(
                        ((InvalidWorkflowDefinitionException) e).getValidation().isValid()).isFalse());

        assertThat(workflows.history(workflowId)).isEmpty();
    }

    @Test
    void activateWithoutADraftIsRefused() {
        UUID actor = createUser("ops@securetravels.in", "Ops", Role.MANAGER, "P@ssw0rd");
        UUID workflowId = workflows.createWorkflow("empty", "Empty", null, actor).getId();

        assertThatThrownBy(() -> workflows.activate(workflowId, actor))
                .isInstanceOf(WorkflowStateException.class);
    }

    @Test
    void duplicateSlugIsRefused() {
        UUID actor = createUser("ops@securetravels.in", "Ops", Role.MANAGER, "P@ssw0rd");
        workflows.createWorkflow("taken", "Taken", null, actor);

        assertThatThrownBy(() -> workflows.createWorkflow("taken", "Again", null, actor))
                .isInstanceOf(WorkflowStateException.class);
    }

    @Test
    void pausingMustHaveSomethingRunning() {
        UUID actor = createUser("ops@securetravels.in", "Ops", Role.MANAGER, "P@ssw0rd");
        UUID workflowId = workflows.createWorkflow("never-live", "Never live", null, actor).getId();

        assertThatThrownBy(() -> workflows.pause(workflowId, actor))
                .isInstanceOf(WorkflowStateException.class);
    }
}