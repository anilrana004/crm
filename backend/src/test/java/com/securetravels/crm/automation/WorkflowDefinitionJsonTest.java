package com.securetravels.crm.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.automation.definition.ActionType;
import com.securetravels.crm.automation.definition.BranchConfig;
import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The definition document is data: Jackson must read it back losslessly,
 * reject anything outside the closed enums, and the shape helpers
 * ({@code step()}, {@code BranchConfig.targets()}) must work on a parsed
 * document exactly as on a hand-built one.
 */
class WorkflowDefinitionJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void richDefinitionRoundTripsLosslessly() throws Exception {
        WorkflowDefinition original = WorkflowFixtures.validDefinition();

        WorkflowDefinition reparsed = mapper.readValue(
                mapper.writeValueAsString(original), WorkflowDefinition.class);

        assertThat(reparsed).isEqualTo(original);
        assertThat(reparsed.steps()).hasSize(4);
        assertThat(reparsed.step("send").action()).isEqualTo(ActionType.SEND_MESSAGE);
        assertThat(reparsed.step("send").retry().maxAttempts()).isEqualTo(1);
    }

    @Test
    void unknownActionIsRejectedByJackson() {
        String json = """
                {
                  "schemaVersion": 1, "name": "Bad", "trigger": {"kind": "EVENT",
                    "event": "lead.updated"},
                  "entryConditions": null,
                  "steps": [{"id": "s1", "order": 1, "action": "EXPLODE", "config": {} }]
                }
                """;
        assertThat(jsonErrorMessage(json)).contains("EXPLODE");
    }

    @Test
    void unknownTriggerKindIsRejectedByJackson() {
        String json = """
                {
                  "schemaVersion": 1, "name": "Bad", "trigger": {"kind": "MAGIC",
                    "event": "lead.updated"},
                  "steps": [{"id": "s1", "order": 1, "action": "STOP", "config": {} }]
                }
                """;
        assertThat(jsonFails(json)).isTrue();
    }

    @Test
    void unknownOperatorIsRejectedByJackson() {
        String json = """
                {
                  "schemaVersion": 1, "name": "Bad", "trigger": {"kind": "EVENT",
                    "event": "lead.updated"},
                  "entryConditions": {"operator": "AND",
                    "conditions": [{"field": "status", "op": "MATCHES", "value": "x"}],
                    "children": []},
                  "steps": [{"id": "s1", "order": 1, "action": "STOP", "config": {} }]
                }
                """;
        assertThat(jsonFails(json)).isTrue();
    }

    @Test
    void parsedBranchConfigYieldsItsTargets() throws Exception {
        StepDefinition branch = new StepDefinition("b", 1, ActionType.BRANCH, null,
                Map.of("cases", List.of(
                        Map.of("when", Map.of("field", "status", "op", "EQ", "value", "NEW"),
                                "goto", "s2"),
                        Map.of("when", Map.of("field", "heat", "op", "EQ", "value", "HOT"),
                                "goto", "s3")),
                        "default", "s4"),
                null);
        StepDefinition parsed = mapper.readValue(mapper.writeValueAsString(branch), StepDefinition.class);

        assertThat(parsed.branchTargets()).containsExactly("s2", "s3", "s4");
        assertThat(BranchConfig.targets(parsed.config())).containsExactly("s2", "s3", "s4");
    }

    private boolean jsonFails(String json) {
        return !jsonErrorMessage(json).isEmpty();
    }

    private String jsonErrorMessage(String json) {
        try {
            mapper.readValue(json, WorkflowDefinition.class);
            return "";
        } catch (JsonProcessingException e) {
            return e.getMessage();
        }
    }
}