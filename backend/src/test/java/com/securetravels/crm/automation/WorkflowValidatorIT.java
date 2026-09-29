package com.securetravels.crm.automation;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.automation.definition.ActionType;
import com.securetravels.crm.automation.definition.BranchConfig;
import com.securetravels.crm.automation.definition.Condition;
import com.securetravels.crm.automation.definition.ConditionNode;
import com.securetravels.crm.automation.definition.ConditionOp;
import com.securetravels.crm.automation.definition.ConditionOperator;
import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.definition.TriggerDefinition;
import com.securetravels.crm.automation.definition.TriggerKind;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.validation.ValidationResult;
import com.securetravels.crm.automation.validation.WorkflowValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 1 validator rejections. Everything here is a closed-vocabulary check:
 * unknown actions, unknown fields, bad operators, bad value types, dangling
 * branches, unreachable steps, size bounds — and the two Phase 5–6 gates:
 * no scripting/expression injection, no relabelling a promotion as
 * transactional (or vice versa).
 */
class WorkflowValidatorIT extends BaseIT {

    @Autowired private WorkflowValidator validator;

    // ------------------------------------------------------------ happy path

    @Test
    void theCanonicalWorkflowIsValid() {
        assertThat(resultOf(WorkflowFixtures.validDefinition()).isValid()).isTrue();
    }

    @Test
    void sixFieldCronIsValid() {
        assertThat(resultOf(WorkflowFixtures.withTrigger(new TriggerDefinition(
                TriggerKind.CRON, null, "lead", "0 0 9 * * ?", null, null))).isValid()).isTrue();
    }

    @Test
    void dateOffsetOnADateFieldIsValid() {
        assertThat(resultOf(WorkflowFixtures.withTrigger(new TriggerDefinition(
                TriggerKind.DATE_OFFSET, null, "lead", null, "travelDate", 0))).isValid()).isTrue();
    }

    // ------------------------------------------------------- injection walls

    @Test
    void spelInjectionInAConditionFieldIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.conditionGate(new Condition(
                        "T(java.lang.Runtime).getRuntime()", ConditionOp.EQ, "evil")),
                "allow-list");
    }

    @Test
    void elInjectionInAConditionFieldIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.conditionGate(new Condition(
                        "#{systemProperties['user.home']}", ConditionOp.EQ, "evil")),
                "allow-list");
    }

    @Test
    void injectionInAnUpdateFieldIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.UPDATE_FIELD, Map.of(
                        "field", "runtime.exec('#{...}')", "value", "x")),
                "allow-list");
    }

    @Test
    void injectionInABranchGuardIsRejectedEvenWhenParsedAsPlainJson() {
        Map<String, Object> config = Map.of(
                "cases", List.of(Map.of(
                        "when", Map.of("operator", "AND",
                                "conditions", List.of(Map.of(
                                        "field", "T(java.lang.Runtime).getRuntime()",
                                        "op", "EQ", "value", "evil")),
                                "children", List.of()),
                        "goto", "stop")),
                "default", "stop");
        List<StepDefinition> steps = List.of(
                new StepDefinition("branch", 1, ActionType.BRANCH, null, config, null),
                new StepDefinition("stop", 2, ActionType.STOP, null, Map.of(), null));
        WorkflowDefinition def = WorkflowFixtures.withSteps(TriggerDefinition.event("lead.updated"), steps);
        assertThat(BranchConfig.targets(config)).contains("stop");
        assertErrorContainsKeyword(def, "allow-list");
    }

    // ------------------------------------------------------- condition shape

    @Test
    void operatorIncompatibleWithFieldTypeIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.conditionGate(new Condition("consentGiven", ConditionOp.IN, List.of("true"))),
                "Operator IN is not valid");
    }

    @Test
    void valueTypeMismatchIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.conditionGate(new Condition("budget", ConditionOp.EQ, "not-a-number")),
                "does not match field 'budget' of type NUMBER");
    }

    @Test
    void nullOnlyOperatorsRejectAValue() {
        assertErrorContainsKeyword(
                WorkflowFixtures.conditionGate(new Condition("travelDate", ConditionOp.IS_NULL, "x")),
                "Operator IS_NULL is not valid");
    }

    @Test
    void enumValueOutsideAllowListIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.conditionGate(new Condition("status", ConditionOp.EQ, "NOT_A_STATUS")),
                "ENUM");
    }

    @Test
    void notNodeMustHaveExactlyOneChild() {
        ConditionNode twoChildren = new ConditionNode(ConditionOperator.NOT, List.of(), List.of(
                ConditionNode.leaf(new Condition("status", ConditionOp.EQ, "NEW")),
                ConditionNode.leaf(new Condition("heat", ConditionOp.EQ, "HOT"))));
        WorkflowDefinition def = new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Not", null,
                TriggerDefinition.event("lead.updated"), twoChildren,
                List.of(new StepDefinition("stop", 1, ActionType.STOP, null, Map.of(), null)));
        assertErrorContainsKeyword(def, "exactly one child");
    }

    // ------------------------------------------------------------- trigger

    @Test
    void cryptTriggerRejectsNumericOnlyEventName() {
        // A config dragon trying to smuggle `cron: true`-style rot through EVENT
        // must still name a real entity.action pair.
        assertErrorContainsKeyword(
                WorkflowFixtures.withTrigger(TriggerDefinition.event("lead.")),
                "{entity}.{action}");
    }

    @Test
    void fiveFieldCronIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.withTrigger(new TriggerDefinition(
                        TriggerKind.CRON, null, "lead", "0 0 9 * *", null, null)),
                "six-field");
    }

    @Test
    void dateOffsetOnANonDateFieldIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.withTrigger(new TriggerDefinition(
                        TriggerKind.DATE_OFFSET, null, "lead", null, "budget", 0)),
                "not a date field");
    }

    // --------------------------------------------------------------- actions

    @Test
    void updateFieldRejectsADerivedField() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.UPDATE_FIELD, Map.of(
                        "field", "travelDate", "value", "today")),
                "cannot write");
    }

    @Test
    void updateFieldRejectsAForeignEntityField() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.UPDATE_FIELD, Map.of(
                        "field", "totalAmount", "value", "100")),
                "not in the lead allow-list");
    }

    @Test
    void updateFieldAcceptsAWritableFieldWithMatchingValue() {
        assertThat(resultOf(WorkflowFixtures.singleStep(ActionType.UPDATE_FIELD, Map.of(
                "field", "followUpDate", "value", "today+1"))).isValid()).isTrue();
    }

    @Test
    void createTaskUserAssigneeNeedsAUuid() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.CREATE_TASK, Map.of(
                        "type", "INITIAL_CALL", "assignee", "USER")),
                "'userId' must be a UUID");
    }

    @Test
    void createTaskNeedsASchedulingKey() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.CREATE_TASK, Map.of(
                        "type", "INITIAL_CALL", "assignee", "OWNER")),
                "dueInMinutes' or 'dueDateField");
    }

    @Test
    void waitRefusesZero() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.WAIT, Map.of("minutes", 0)),
                "between 1 and 129600");
    }

    @Test
    void waitRefusesBeyondConfiguredHorizon() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.WAIT, Map.of("minutes", 200_000)),
                "between 1 and 129600");
    }

    @Test
    void callWebhookRefusesPlainHttp() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.CALL_WEBHOOK, Map.of(
                        "url", "http://hooks.example.com/pay")),
                "must use https");
    }

    @Test
    void callWebhookRefusesIpLiterals() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.CALL_WEBHOOK, Map.of(
                        "url", "https://127.0.0.1/hook")),
                "must use a DNS hostname");
    }

    @Test
    void callWebhookAcceptsHttpsHost() {
        assertThat(resultOf(WorkflowFixtures.singleStep(ActionType.CALL_WEBHOOK, Map.of(
                "url", "https://hooks.example.com/pay", "method", "POST",
                "timeoutSeconds", 30))).isValid()).isTrue();
    }

    // ----------------------------------------------------------- SEND_MESSAGE

    @Test
    void sendMessageRequiresAChannel() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                        "templateCode", "BOOKING_CONFIRMED", "to", "customerName")),
                "requires a 'channel'");
    }

    @Test
    void sendMessageRejectsAnUnknownTemplate() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                        "channel", "WHATSAPP", "templateCode", "NOT_A_TEMPLATE",
                        "purpose", "TRANSACTIONAL", "to", "customerName")),
                "no enabled WHATSAPP template");
    }

    @Test
    void sendMessageRejectsAnUnapprovedTemplate() {
        jdbcTemplate.update("UPDATE whatsapp_templates SET approval_status = 'REJECTED' WHERE code = 'BOOKING_CONFIRMED'");
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                        "channel", "WHATSAPP", "templateCode", "BOOKING_CONFIRMED",
                        "purpose", "TRANSACTIONAL", "to", "customerName")),
                "not APPROVED");
    }

    @Test
    void promotionalTemplateCannotBeRelabelledTransactional() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                        "channel", "WHATSAPP", "templateCode", "POST_TRIP_REVIEW",
                        "purpose", "TRANSACTIONAL", "to", "customerName")),
                "is MARKETING");
    }

    @Test
    void promotionalTemplateWithMarketingPurposeIsValid() {
        assertThat(resultOf(WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                "channel", "WHATSAPP", "templateCode", "POST_TRIP_REVIEW",
                "purpose", "MARKETING", "to", "customerName"))).isValid()).isTrue();
    }

    @Test
    void transactionalEmailTemplateIsValid() {
        assertThat(resultOf(WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                "channel", "EMAIL", "templateCode", "PACKAGE_DETAILS",
                "purpose", "TRANSACTIONAL", "to", "customerName"))).isValid()).isTrue();
    }

    @Test
    void sendMessageRejectsUnknownRecipientField() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.SEND_MESSAGE, Map.of(
                        "channel", "EMAIL", "templateCode", "PACKAGE_DETAILS",
                        "purpose", "TRANSACTIONAL", "to", "notAField")),
                "'to' must name a TEXT field");
    }

    // ------------------------------------------------- ghost branches, size

    @Test
    void branchTargetingAnUnknownStepIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.BRANCH, Map.of(
                        "cases", List.of(Map.of(
                                "when", Map.of("operator", "AND", "conditions", List.of(),
                                        "children", List.of()),
                                "goto", "ghost")),
                        "default", "ghost")),
                "targets unknown step");
    }

    @Test
    void branchLoopingToItselfIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.singleStep(ActionType.BRANCH, Map.of(
                        "cases", List.of(Map.of(
                                "when", Map.of("operator", "AND", "conditions", List.of(),
                                        "children", List.of()),
                                "goto", "step1")),
                        "default", "step1")),
                "loops to itself");
    }

    @Test
    void unreachableStepIsRejected() {
        List<StepDefinition> steps = List.of(
                new StepDefinition("branch", 1, ActionType.BRANCH, null,
                        Map.of("cases", List.of(Map.of(
                                "when", Map.of("operator", "AND", "conditions", List.of(),
                                        "children", List.of()),
                                "goto", "stopB")),
                                "default", "stopB"),
                        null),
                new StepDefinition("stopA", 2, ActionType.STOP, null, Map.of(), null),
                new StepDefinition("stopB", 3, ActionType.STOP, null, Map.of(), null));
        assertErrorContainsKeyword(
                WorkflowFixtures.withSteps(TriggerDefinition.event("lead.updated"), steps),
                "unreachable");
    }

    @Test
    void aDefinitionBeyondTheStepBudgetIsRejected() {
        assertErrorContainsKeyword(
                WorkflowFixtures.repeatedSteps(ActionType.STOP, Map.of()),
                "exceeds the maximum of 30 steps");
    }

    @Test
    void malformedStepIdIsRejected() {
        WorkflowDefinition def = new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Bad id", null,
                TriggerDefinition.event("lead.updated"), null,
                List.of(new StepDefinition("bad id!", 1, ActionType.STOP, null, Map.of(), null)));
        assertErrorContainsKeyword(def, "must match");
    }

    // ---------------------------------------------------------------- utils

    private ValidationResult resultOf(WorkflowDefinition def) {
        return validator.validate(def);
    }

    private void assertErrorContainsKeyword(WorkflowDefinition def, String keyword) {
        ValidationResult result = validator.validate(def);
        assertThat(result.isValid())
                .as("expected an error containing '%s'; got %s", keyword, result.issues())
                .isFalse();
        assertThat(result.errors())
                .as("no error mentions '%s'", keyword)
                .anyMatch(i -> i.message().contains(keyword));
    }
}