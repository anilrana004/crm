package com.securetravels.crm.automation;

import com.securetravels.crm.automation.definition.ActionType;
import com.securetravels.crm.automation.definition.Condition;
import com.securetravels.crm.automation.definition.ConditionNode;
import com.securetravels.crm.automation.definition.ConditionOp;
import com.securetravels.crm.automation.definition.ConditionOperator;
import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.definition.TriggerDefinition;
import com.securetravels.crm.automation.definition.WorkflowDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builders for Module 1 tests: one known-good definition plus piecewise
 * mutations so validator tests assert on one broken thing at a time.
 */
public final class WorkflowFixtures {

    private WorkflowFixtures() {
    }

    /** Exactly the shape a real editor would save for "speed to lead". */
    public static WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION,
                "Speed to lead",
                "Customer is interested; create the task, push the follow-up date, send the whatsapp.",
                TriggerDefinition.event("lead.updated"),
                new ConditionNode(ConditionOperator.AND,
                        List.of(new Condition("status", ConditionOp.EQ, "INTERESTED"),
                                new Condition("heat", ConditionOp.EQ, "HOT")),
                        List.of()),
                List.of(
                        new StepDefinition("task", 1, ActionType.CREATE_TASK, null,
                                Map.of("type", "INITIAL_CALL", "assignee", "OWNER", "dueInMinutes", 60),
                                null),
                        new StepDefinition("date", 2, ActionType.UPDATE_FIELD, null,
                                Map.of("field", "followUpDate", "value", "today+1"),
                                null),
                        new StepDefinition("send", 3, ActionType.SEND_MESSAGE, null,
                                Map.of("channel", "WHATSAPP", "templateCode", "BOOKING_CONFIRMED",
                                        "purpose", "TRANSACTIONAL", "to", "customerName",
                                        "bodyValues", List.of("{customerName}", "{destination}")),
                                null),
                        new StepDefinition("stop", 4, ActionType.STOP, null, Map.of(), null)));
    }

    /** Single-condition entry gate, a single STOP step. */
    public static WorkflowDefinition conditionGate(Condition condition) {
        return new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION,
                "Gate " + condition.field(),
                null,
                TriggerDefinition.event("lead.updated"),
                ConditionNode.leaf(condition),
                List.of(new StepDefinition("stop", 1, ActionType.STOP, null, Map.of(), null)));
    }

    /** N steps of a given action, each carrying the same config. */
    public static WorkflowDefinition repeatedSteps(ActionType action, Map<String, Object> config) {
        List<StepDefinition> steps = new ArrayList<>();
        for (int i = 0; i < 31; i++) {
            steps.add(new StepDefinition("step" + i, i + 1, action, null, config, null));
        }
        return new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Repeated " + action, null,
                TriggerDefinition.event("lead.updated"), null, steps);
    }

    /** A definition whose trigger is the given one (used for cron/offset cases). */
    public static WorkflowDefinition withTrigger(TriggerDefinition trigger) {
        return new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Trigger", null,
                trigger, null,
                List.of(new StepDefinition("stop", 1, ActionType.STOP, null, Map.of(), null)));
    }

    public static WorkflowDefinition singleStep(ActionType action, Map<String, Object> config) {
        return new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Single " + action, null,
                TriggerDefinition.event("lead.updated"), null,
                List.of(new StepDefinition("step1", 1, action, null, config, null)));
    }

    public static WorkflowDefinition withSteps(TriggerDefinition trigger, List<StepDefinition> steps) {
        return new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Steps", null, trigger, null, steps);
    }
}