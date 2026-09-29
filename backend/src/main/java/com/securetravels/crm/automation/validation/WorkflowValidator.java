package com.securetravels.crm.automation.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.automation.definition.ActionType;
import com.securetravels.crm.automation.definition.Condition;
import com.securetravels.crm.automation.definition.ConditionNode;
import com.securetravels.crm.automation.definition.ConditionOp;
import com.securetravels.crm.automation.definition.ConditionOperator;
import com.securetravels.crm.automation.definition.RetryPolicy;
import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.definition.TriggerDefinition;
import com.securetravels.crm.automation.definition.TriggerKind;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.field.FieldDef;
import com.securetravels.crm.automation.field.FieldRegistry;
import com.securetravels.crm.automation.field.FieldType;
import com.securetravels.crm.communications.WhatsAppTemplate;
import com.securetravels.crm.communications.WhatsAppTemplateRepository;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.template.ChannelTemplate;
import com.securetravels.crm.communications.template.ChannelTemplateRepository;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.common.config.AppProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a full workflow definition against the closed vocabulary
 * (Phase 6 Module 1).
 *
 * <p>Everything here is a rejection or warning, never evaluation: the JSON
 * document is data, and anything that is not in the action enum, the field
 * registry, or an operator's declared values is refused. In particular no
 * expression language, reflection key, or provider-specific trick survives
 * this pass even if it survives Jackson's bind (the action enum and registry
 * are the only two doors, and neither reads strings as code).
 *
 * <p>Template checks use the real catalogues ({@code whatsapp_templates} for
 * WHATSAPP, {@code channel_templates} for EMAIL/SMS) so "MARKETING send
 * without a MARKETING template" and "promotion relabelled transactional" are
 * rejected before a run can be created.
 */
@Component
public class WorkflowValidator {

    private static final Set<String> USER_ROLES = Set.of("SALES", "OPS", "MANAGER", "ADMIN", "CEO");
    private static final Set<String> TASK_TYPES = Set.of(
            "INITIAL_CALL", "FOLLOW_UP_1D", "FOLLOW_UP_3D", "FOLLOW_UP_8D", "FOLLOW_UP_15D",
            "QUOTATION", "PAYMENT_REMINDER", "OPS", "REVIEW", "CUSTOM");
    private static final Set<String> ASSIGNEE_METHODS = Set.of("OWNER", "ROUND_ROBIN", "ROLE", "USER");
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH");
    private static final Set<String> ASSIGN_OWNER_METHODS = Set.of("ROUND_ROBIN", "SPECIFIC");

    private final WhatsAppTemplateRepository whatsappTemplates;
    private final ChannelTemplateRepository channelTemplates;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public WorkflowValidator(WhatsAppTemplateRepository whatsappTemplates,
                             ChannelTemplateRepository channelTemplates,
                             AppProperties props,
                             ObjectMapper objectMapper) {
        this.whatsappTemplates = whatsappTemplates;
        this.channelTemplates = channelTemplates;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ entry

    public ValidationResult validate(WorkflowDefinition def) {
        ValidationResult result = new ValidationResult();
        if (def == null) {
            result.add(ValidationIssue.error("Definition is empty"));
            return result;
        }
        if (def.schemaVersion() != WorkflowDefinition.CURRENT_SCHEMA_VERSION) {
            result.add(ValidationIssue.error(
                    "Unsupported schemaVersion " + def.schemaVersion()
                            + " (expected " + WorkflowDefinition.CURRENT_SCHEMA_VERSION + ")"));
            return result;
        }
        if (def.name() == null || def.name().isBlank()) {
            result.add(ValidationIssue.error("Definition has no name"));
        } else if (def.name().length() > 200) {
            result.add(ValidationIssue.error("Definition name exceeds 200 characters"));
        }
        if (def.description() != null && def.description().length() > 1000) {
            result.add(ValidationIssue.error("Description exceeds 1000 characters"));
        }

        if (def.trigger() == null) {
            result.add(ValidationIssue.error("Definition has no trigger"));
            return result;
        }
        String entity = validateTrigger(result, def.trigger());
        validateConditions(result, entity, def.entryConditions(), "entry conditions");
        validateSteps(result, entity, def.steps());
        return result;
    }

    // --------------------------------------------------------------- trigger

    private String validateTrigger(ValidationResult result, TriggerDefinition trigger) {
        return switch (trigger.kind()) {
            case EVENT -> validateEvent(result, trigger);
            case CRON -> validateCron(result, trigger);
            case DATE_OFFSET -> validateDateOffset(result, trigger);
        };
    }

    private String validateEvent(ValidationResult result, TriggerDefinition trigger) {
        if (trigger.event() == null || !trigger.event().matches("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$")) {
            result.add(ValidationIssue.error(
                    "EVENT trigger 'event' must be of the form {entity}.{action} "
                            + "(e.g. lead.updated)"));
            return null;
        }
        String entity = trigger.event().substring(0, trigger.event().indexOf('.'));
        if (!FieldRegistry.isKnownEntity(entity)) {
            result.add(ValidationIssue.error("EVENT trigger references unknown entity '" + entity
                    + "' (allow-list: lead, booking, payment, task, batch, customer, traveller)"));
        }
        return entity;
    }

    private String validateCron(ValidationResult result, TriggerDefinition trigger) {
        if (trigger.entity() == null || !FieldRegistry.isKnownEntity(trigger.entity())) {
            result.add(ValidationIssue.error(
                    "CRON trigger requires a known 'entity' (allow-list: lead, booking, ...)"));
        }
        if (trigger.cron() == null || !isSixFieldCron(trigger.cron())) {
            result.add(ValidationIssue.error(
                    "CRON trigger requires a valid six-field cron 'expression' "
                            + "(e.g. \"0 0 9 * * ?\")"));
        }
        return trigger.entity();
    }

    private String validateDateOffset(ValidationResult result, TriggerDefinition trigger) {
        if (trigger.entity() == null || !FieldRegistry.isKnownEntity(trigger.entity())) {
            result.add(ValidationIssue.error(
                    "DATE_OFFSET trigger requires a known 'entity'"));
            return null;
        }
        if (trigger.dateField() == null) {
            result.add(ValidationIssue.error("DATE_OFFSET trigger requires a 'dateField'"));
            return trigger.entity();
        }
        Optional<FieldDef> field = FieldRegistry.lookup(trigger.entity(), trigger.dateField());
        if (field.isEmpty()) {
            result.add(ValidationIssue.error("DATE_OFFSET 'dateField' '" + trigger.dateField()
                    + "' is not in the field allow-list for " + trigger.entity()));
        } else if (field.get().type() != FieldType.DATE && field.get().type() != FieldType.DATE_TIME) {
            result.add(ValidationIssue.error("DATE_OFFSET 'dateField' '" + trigger.dateField()
                    + "' is not a date field"));
        }
        if (trigger.offsetDays() != null && Math.abs(trigger.offsetDays()) > 3650) {
            result.add(ValidationIssue.error("DATE_OFFSET 'offsetDays' is out of range"));
        }
        return trigger.entity();
    }

    // ------------------------------------------------------------ conditions

    private void validateConditions(ValidationResult result, String entity, ConditionNode node,
                                    String where) {
        if (node == null) {
            return;
        }
        if (entity == null) {
            result.add(ValidationIssue.error("Conditions require a trigger entity", where));
            return;
        }
        validateNode(result, entity, node, where);
    }

    private void validateNode(ValidationResult result, String entity, ConditionNode node, String where) {
        if (node.operator() == null) {
            result.add(ValidationIssue.error("Condition node has no operator", where));
            return;
        }
        List<Condition> conditions = node.conditions() == null ? List.of() : node.conditions();
        List<ConditionNode> children = node.children() == null ? List.of() : node.children();
        switch (node.operator()) {
            case NOT -> {
                if (children.size() != 1 || !conditions.isEmpty()) {
                    result.add(ValidationIssue.error(
                            "NOT node must contain exactly one child and no leaves", where));
                }
            }
            case AND, OR -> {
                if (conditions.isEmpty() && children.isEmpty()) {
                    result.add(ValidationIssue.error(
                            node.operator() + " node must contain at least one condition or child", where));
                }
                if (node.operator() == ConditionOperator.AND && conditions.size() > 1 && !children.isEmpty()) {
                    result.add(ValidationIssue.warning(
                            "AND node mixes leaves and children; prefer explicit nesting", where));
                }
            }
        }
        for (Condition c : conditions) {
            validateLeaf(result, entity, c, where);
        }
        for (ConditionNode child : children) {
            validateNode(result, entity, child, where);
        }
    }

    private void validateLeaf(ValidationResult result, String entity, Condition c, String where) {
        if (c.field() == null || c.field().isBlank()) {
            result.add(ValidationIssue.error("Condition has no field", where));
            return;
        }
        Optional<FieldDef> def = FieldRegistry.lookup(entity, c.field());
        if (def.isEmpty()) {
            // This is the injection wall: anything not in the allow-list — SpEL,
            // OGNL, T(java.lang.Runtime), #{...} — is rejected here verbatim.
            result.add(ValidationIssue.error("Condition field '" + c.field() + "' is not in the "
                    + entity + " field allow-list", where));
            return;
        }
        if (c.op() == null) {
            result.add(ValidationIssue.error("Condition '" + c.field() + "' has no operator", where));
            return;
        }
        boolean opOk = switch (c.op()) {
            case EQ, NEQ -> true;
            case GT, GTE, LT, LTE ->
                    def.get().type() == FieldType.NUMBER
                            || def.get().type() == FieldType.DATE
                            || def.get().type() == FieldType.DATE_TIME;
            case IN, NOT_IN ->
                    def.get().type() == FieldType.TEXT
                            || def.get().type() == FieldType.NUMBER
                            || def.get().type() == FieldType.UUID
                            || def.get().type() == FieldType.ENUM;
            case CONTAINS, STARTS_WITH -> def.get().type() == FieldType.TEXT;
            case IS_NULL, NOT_NULL -> c.value() == null;
        };
        if (!opOk) {
            result.add(ValidationIssue.error("Operator " + c.op() + " is not valid for field '"
                    + c.field() + "' of type " + def.get().type(), where));
            return;
        }
        if (c.op() != ConditionOp.IS_NULL && c.op() != ConditionOp.NOT_NULL
                && !valueMatches(def.get(), c.value())) {
            result.add(ValidationIssue.error("Value " + describe(c.value())
                    + " does not match field '" + c.field() + "' of type " + def.get().type(), where));
        }
    }

    // ---------------------------------------------------------------- steps

    private void validateSteps(ValidationResult result, String entity, List<StepDefinition> steps) {
        if (steps == null || steps.isEmpty()) {
            result.add(ValidationIssue.error("Definition has no steps"));
            return;
        }
        if (steps.size() > props.getAutomation().getMaxStepsPerWorkflow()) {
            result.add(ValidationIssue.error("Definition exceeds the maximum of "
                    + props.getAutomation().getMaxStepsPerWorkflow() + " steps"));
            return;
        }

        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (StepDefinition step : steps) {
            if (step == null) {
                result.add(ValidationIssue.error("A step is null"));
                continue;
            }
            if (step.id() == null || !step.id().matches("^[a-zA-Z0-9_-]{1,64}$")) {
                result.add(ValidationIssue.error("Step id '" + step.id()
                        + "' must match ^[a-zA-Z0-9_-]{1,64}$"));
            } else if (!ids.add(step.id())) {
                result.add(ValidationIssue.error("Duplicate step id '" + step.id() + "'"));
            }
            if (!orders.add(step.order())) {
                result.add(ValidationIssue.error("Duplicate step order " + step.order()
                        + " at '" + step.id() + "'"));
            }
            if (step.action() == null) {
                result.add(ValidationIssue.error("Step '" + step.id() + "' has no action"));
                continue;
            }
            if (step.retry() != null) {
                validateRetry(result, step);
            }
            validateConditions(result, entity, step.when(), "step '" + step.id() + "' guard");
            validateActionConfig(result, entity, step);
        }

        validateBranchesAndReachability(result, steps);
    }

    private void validateRetry(ValidationResult result, StepDefinition step) {
        RetryPolicy retry = step.retry();
        if (retry.maxAttempts() < 1 || retry.maxAttempts() > 10) {
            result.add(ValidationIssue.error("Step '" + step.id()
                    + "' retry.maxAttempts must be between 1 and 10"));
        }
        if (retry.backoffMillis() < 0) {
            result.add(ValidationIssue.error("Step '" + step.id() + "' retry.backoffMillis must be >= 0"));
        }
    }

    private void validateActionConfig(ValidationResult result, String entity, StepDefinition step) {
        Map<String, Object> cfg = step.config() == null ? Map.of() : step.config();
        switch (step.action()) {
            case CREATE_TASK -> validateCreateTask(result, entity, step, cfg);
            case ASSIGN_OWNER -> validateAssignOwner(result, step, cfg);
            case UPDATE_FIELD -> validateUpdateField(result, entity, step, cfg);
            case SEND_MESSAGE -> validateSendMessage(result, entity, step, cfg);
            case NOTIFY_USER -> validateNotifyUser(result, step, cfg);
            case REQUEST_APPROVAL -> validateRequestApproval(result, step, cfg);
            case ADD_TAG -> validateAddTag(result, step, cfg);
            case ENROLL_SEQUENCE -> validateEnrollSequence(result, step, cfg);
            case WAIT -> validateWait(result, step, cfg);
            case BRANCH -> validateBranch(result, entity, step, cfg);
            case CALL_WEBHOOK -> validateCallWebhook(result, step, cfg);
            case STOP -> { /* terminating action; no config requirements */ }
        }
    }

    private void validateCreateTask(ValidationResult result, String entity, StepDefinition step,
                                    Map<String, Object> cfg) {
        String type = stringOf(cfg.get("type"));
        if (!TASK_TYPES.contains(type)) {
            result.add(ValidationIssue.error("CREATE_TASK 'type' must be one of " + TASK_TYPES,
                    step.id()));
        }
        String assignee = stringOf(cfg.get("assignee"));
        if (!ASSIGNEE_METHODS.contains(assignee)) {
            result.add(ValidationIssue.error("CREATE_TASK 'assignee' must be one of "
                    + ASSIGNEE_METHODS, step.id()));
        } else if (assignee.equals("ROLE")) {
            if (!USER_ROLES.contains(stringOf(cfg.get("role")))) {
                result.add(ValidationIssue.error("CREATE_TASK 'role' must be a user role",
                        step.id()));
            }
        } else if (assignee.equals("USER")) {
            if (!isUuid(cfg.get("userId"))) {
                result.add(ValidationIssue.error("CREATE_TASK 'userId' must be a UUID", step.id()));
            }
        }
        Integer dueMinutes = intOf(cfg.get("dueInMinutes"));
        String dueField = stringOf(cfg.get("dueDateField"));
        if (dueMinutes == null && dueField == null) {
            result.add(ValidationIssue.error("CREATE_TASK needs 'dueInMinutes' or 'dueDateField'",
                    step.id()));
        } else if (dueMinutes != null && dueMinutes < 1) {
            result.add(ValidationIssue.error("CREATE_TASK 'dueInMinutes' must be >= 1", step.id()));
        } else if (dueField != null && FieldRegistry.lookup(entity, dueField)
                .map(f -> f.type() != FieldType.DATE && f.type() != FieldType.DATE_TIME)
                .orElse(true)) {
            result.add(ValidationIssue.error("CREATE_TASK 'dueDateField' '" + dueField
                    + "' is not a date field on " + entity, step.id()));
        }
        if (intOf(cfg.get("slaHours")) != null && intOf(cfg.get("slaHours")) < 0) {
            result.add(ValidationIssue.error("CREATE_TASK 'slaHours' must be >= 0", step.id()));
        }
    }

    private void validateAssignOwner(ValidationResult result, StepDefinition step,
                                     Map<String, Object> cfg) {
        String method = stringOf(cfg.get("method"));
        if (!ASSIGN_OWNER_METHODS.contains(method)) {
            result.add(ValidationIssue.error("ASSIGN_OWNER 'method' must be one of "
                    + ASSIGN_OWNER_METHODS, step.id()));
        } else if (method.equals("SPECIFIC") && !isUuid(cfg.get("userId"))) {
            result.add(ValidationIssue.error("ASSIGN_OWNER 'userId' must be a UUID for SPECIFIC",
                    step.id()));
        }
    }

    private void validateUpdateField(ValidationResult result, String entity, StepDefinition step,
                                     Map<String, Object> cfg) {
        String field = stringOf(cfg.get("field"));
        Optional<FieldDef> def = field == null ? Optional.empty() : FieldRegistry.lookup(entity, field);
        if (def.isEmpty()) {
            result.add(ValidationIssue.error("UPDATE_FIELD 'field' '" + field
                    + "' is not in the " + entity + " allow-list", step.id()));
            return;
        }
        if (!def.get().writable()) {
            result.add(ValidationIssue.error("UPDATE_FIELD cannot write '" + field
                    + "' (derived or system-maintained)", step.id()));
        }
        String fromField = stringOf(cfg.get("fromField"));
        Object value = cfg.get("value");
        if (fromField == null && value == null) {
            result.add(ValidationIssue.error("UPDATE_FIELD needs 'value' or 'fromField'", step.id()));
            return;
        }
        if (fromField != null) {
            Optional<FieldDef> from = FieldRegistry.lookup(entity, fromField);
            if (from.isEmpty()) {
                result.add(ValidationIssue.error("UPDATE_FIELD 'fromField' '" + fromField
                        + "' is not in the allow-list", step.id()));
            } else if (from.get().type() != def.get().type()) {
                result.add(ValidationIssue.error("UPDATE_FIELD cannot copy " + fromField
                        + " (" + from.get().type() + ") into " + field
                        + " (" + def.get().type() + ")", step.id()));
            }
        } else if (!valueMatches(def.get(), value)) {
            result.add(ValidationIssue.error("UPDATE_FIELD value " + describe(value)
                    + " does not match field '" + field + "' of type " + def.get().type(), step.id()));
        }
    }

    private void validateSendMessage(ValidationResult result, String entity, StepDefinition step,
                                     Map<String, Object> cfg) {
        CommunicationChannel channel = null;
        try {
            String ch = stringOf(cfg.get("channel"));
            if (ch != null) {
                channel = CommunicationChannel.valueOf(ch);
            } else {
                result.add(ValidationIssue.error("SEND_MESSAGE requires a 'channel'", step.id()));
                return;
            }
        } catch (IllegalArgumentException e) {
            result.add(ValidationIssue.error("SEND_MESSAGE 'channel' must be WHATSAPP, EMAIL or SMS",
                    step.id()));
            return;
        }

        String code = stringOf(cfg.get("templateCode"));
        if (code == null || code.isBlank()) {
            result.add(ValidationIssue.error("SEND_MESSAGE requires a 'templateCode'", step.id()));
            return;
        }

        Purpose declared = null;
        String purposeRaw = stringOf(cfg.get("purpose"));
        if (purposeRaw != null) {
            try {
                declared = Purpose.valueOf(purposeRaw);
            } catch (IllegalArgumentException e) {
                result.add(ValidationIssue.error("SEND_MESSAGE 'purpose' must be TRANSACTIONAL "
                        + "or MARKETING", step.id()));
                return;
            }
        }

        TemplateResolution template = resolveTemplate(channel, code);
        if (template == null) {
            result.add(ValidationIssue.error("SEND_MESSAGE references no enabled "
                    + channel + " template '" + code + "'", step.id()));
            return;
        }
        if (!template.approved()) {
            result.add(ValidationIssue.error("SEND_MESSAGE references " + channel
                    + " template '" + code + "' which is not APPROVED", step.id()));
            return;
        }
        if (declared != null && declared != template.category()) {
            result.add(ValidationIssue.error("SEND_MESSAGE declares purpose " + declared + " but "
                    + channel + " template '" + code + "' is " + template.category()
                    + " — a promotion cannot be relabelled and vice versa", step.id()));
        }

        String to = stringOf(cfg.get("to"));
        Optional<FieldDef> toDef = to == null ? Optional.empty() : FieldRegistry.lookup(entity, to);
        if (to == null || toDef.isEmpty() || toDef.get().type() != FieldType.TEXT) {
            result.add(ValidationIssue.error("SEND_MESSAGE 'to' must name a TEXT field on "
                    + entity + " carrying the recipient (e.g. mobile / email)", step.id()));
        }
        validateBodyValues(result, entity, step, cfg);
    }

    private void validateBodyValues(ValidationResult result, String entity, StepDefinition step,
                                    Map<String, Object> cfg) {
        Object raw = cfg.get("bodyValues");
        if (!(raw instanceof List<?> values)) {
            return;
        }
        int i = 0;
        for (Object v : values) {
            if (v instanceof String s) {
                // A known field name is pulled at run time; anything else is a literal.
                if (s.startsWith("{") && s.endsWith("}")) {
                    String field = s.substring(1, s.length() - 1);
                    if (FieldRegistry.lookup(entity, field).isEmpty()) {
                        result.add(ValidationIssue.error("SEND_MESSAGE bodyValues[" + i + "] "
                                + "references unknown field '" + field + "'", step.id()));
                    }
                }
            } else if (!(v instanceof Number) && v != null) {
                result.add(ValidationIssue.error("SEND_MESSAGE bodyValues[" + i
                        + "] must be a string or number", step.id()));
            }
            i++;
        }
    }

    private void validateNotifyUser(ValidationResult result, StepDefinition step,
                                    Map<String, Object> cfg) {
        String role = stringOf(cfg.get("role"));
        String userId = stringOf(cfg.get("userId"));
        if (role != null) {
            if (!USER_ROLES.contains(role)) {
                result.add(ValidationIssue.error("NOTIFY_USER 'role' must be a user role", step.id()));
            }
        } else if (userId == null || !isUuid(cfg.get("userId"))) {
            result.add(ValidationIssue.error("NOTIFY_USER needs a valid 'role' or 'userId'",
                    step.id()));
        }
        if (stringOf(cfg.get("title")) == null || stringOf(cfg.get("title")).isBlank()) {
            result.add(ValidationIssue.error("NOTIFY_USER requires a 'title'", step.id()));
        }
    }

    private void validateRequestApproval(ValidationResult result, StepDefinition step,
                                         Map<String, Object> cfg) {
        String role = stringOf(cfg.get("role"));
        if (role == null || !USER_ROLES.contains(role)) {
            result.add(ValidationIssue.error("REQUEST_APPROVAL 'role' must be a user role",
                    step.id()));
        }
        if (stringOf(cfg.get("message")) == null) {
            result.add(ValidationIssue.error("REQUEST_APPROVAL requires a 'message'", step.id()));
        }
    }

    private void validateAddTag(ValidationResult result, StepDefinition step,
                                Map<String, Object> cfg) {
        String tag = stringOf(cfg.get("tag"));
        if (tag == null || tag.isBlank() || tag.length() > 100) {
            result.add(ValidationIssue.error("ADD_TAG 'tag' required, <= 100 chars", step.id()));
        }
    }

    private void validateEnrollSequence(ValidationResult result, StepDefinition step,
                                        Map<String, Object> cfg) {
        String slug = stringOf(cfg.get("sequenceSlug"));
        if (slug == null || slug.isBlank() || slug.length() > 100) {
            result.add(ValidationIssue.error("ENROLL_SEQUENCE 'sequenceSlug' required, <= 100 chars",
                    step.id()));
        }
    }

    private void validateWait(ValidationResult result, StepDefinition step, Map<String, Object> cfg) {
        Integer minutes = intOf(cfg.get("minutes"));
        if (minutes == null || minutes < 1 || minutes > props.getAutomation().getMaxWaitMinutes()) {
            result.add(ValidationIssue.error("WAIT 'minutes' must be between 1 and "
                    + props.getAutomation().getMaxWaitMinutes(), step.id()));
        }
    }

    private void validateBranch(ValidationResult result, String entity, StepDefinition step,
                             Map<String, Object> cfg) {
        Object casesRaw = cfg.get("cases");
        if (casesRaw != null && !(casesRaw instanceof List<?> cases)) {
            result.add(ValidationIssue.error("BRANCH 'cases' must be a list", step.id()));
            return;
        }
        if (casesRaw instanceof List<?> cases) {
            if (cases.isEmpty()) {
                result.add(ValidationIssue.warning("BRANCH has no cases; only 'default' can fire",
                        step.id()));
            }
            int i = 0;
            for (Object c : cases) {
                String where = "step '" + step.id() + "' branch case " + i;
                if (!(c instanceof Map<?, ?> caseMap)) {
                    result.add(ValidationIssue.error("BRANCH case " + i + " must be an object",
                            step.id()));
                    i++;
                    continue;
                }
                if (!(caseMap.get("goto") instanceof String)) {
                    result.add(ValidationIssue.error("BRANCH case needs a 'goto' step id",
                            step.id()));
                }
                Object guard = caseMap.get("when");
                if (guard == null) {
                    result.add(ValidationIssue.error("BRANCH case needs a 'when' guard",
                            step.id()));
                } else if (guard instanceof ConditionNode node) {
                    validateConditions(result, entity, node, where);
                } else {
                    try {
                        validateConditions(result, entity,
                                objectMapper.convertValue(guard, ConditionNode.class), where);
                    } catch (IllegalArgumentException e) {
                        result.add(ValidationIssue.error(
                                "BRANCH case 'when' is not a valid condition tree", where));
                    }
                }
                i++;
            }
        }
        if (cfg.get("default") != null && !(cfg.get("default") instanceof String)) {
            result.add(ValidationIssue.error("BRANCH 'default' must be a step id", step.id()));
        }
    }

    private void validateCallWebhook(ValidationResult result, StepDefinition step,
                                     Map<String, Object> cfg) {
        String url = stringOf(cfg.get("url"));
        if (url == null || url.isBlank()) {
            result.add(ValidationIssue.error("CALL_WEBHOOK requires a 'url'", step.id()));
            return;
        }
        // Module 3 will add the runtime allow-list and DNS-rebinding guard; the
        // definition gate already refuses anything but https to a DNS hostname.
        try {
            URI uri = new URI(url);
            if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
                result.add(ValidationIssue.error("CALL_WEBHOOK 'url' must use https", step.id()));
            } else if (uri.getHost() == null || isIpLiteral(uri.getHost())) {
                result.add(ValidationIssue.error("CALL_WEBHOOK 'url' must use a DNS hostname, "
                        + "not an IP literal", step.id()));
            } else if (uri.getUserInfo() != null) {
                result.add(ValidationIssue.error("CALL_WEBHOOK 'url' must not embed userinfo",
                        step.id()));
            }
        } catch (URISyntaxException e) {
            result.add(ValidationIssue.error("CALL_WEBHOOK 'url' is malformed", step.id()));
        }
        String method = stringOf(cfg.get("method"));
        if (method != null && !HTTP_METHODS.contains(method)) {
            result.add(ValidationIssue.error("CALL_WEBHOOK 'method' must be one of " + HTTP_METHODS,
                    step.id()));
        }
        Integer timeout = intOf(cfg.get("timeoutSeconds"));
        if (timeout != null && (timeout < 1 || timeout > 120)) {
            result.add(ValidationIssue.error("CALL_WEBHOOK 'timeoutSeconds' must be 1..120",
                    step.id()));
        }
    }

    // ------------------------------------------- branches & reachability

    private void validateBranchesAndReachability(ValidationResult result,
                                                 List<StepDefinition> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        Set<String> ids = new HashSet<>();
        for (StepDefinition s : steps) {
            if (s != null && s.id() != null) {
                ids.add(s.id());
            }
        }
        StepDefinition entry = steps.stream().min((a, b) -> Integer.compare(a.order(), b.order()))
                .orElse(null);
        if (entry == null) {
            return;
        }

        int nextOrder = Integer.MAX_VALUE;
        for (StepDefinition s : steps) {
            if (s.action() == null) {
                continue;
            }
            List<String> targets = s.action() == ActionType.BRANCH ? s.branchTargets() : List.of();
            for (String target : targets) {
                if (!ids.contains(target)) {
                    result.add(ValidationIssue.error("BRANCH at step '" + s.id()
                            + "' targets unknown step '" + target + "'", s.id()));
                } else if (target.equals(s.id())) {
                    result.add(ValidationIssue.error("BRANCH at step '" + s.id()
                            + "' loops to itself", s.id()));
                }
            }
        }

        // Reachability: entry is the lowest order; a normal step flows to the
        // next-higher order; BRANCH flows only to its targets; STOP terminates.
        Map<String, List<String>> successors = new HashMap<>();
        for (StepDefinition s : steps) {
            if (s.action() == null) {
                successors.put(s.id(), List.of());
                continue;
            }
            if (s.action() == ActionType.BRANCH) {
                successors.put(s.id(), s.branchTargets());
            } else if (s.action() == ActionType.STOP) {
                successors.put(s.id(), List.of());
            } else {
                StepDefinition next = steps.stream()
                        .filter(o -> o.order() > s.order() && o.action() != null)
                        .min((a, b) -> Integer.compare(a.order(), b.order()))
                        .orElse(null);
                successors.put(s.id(), next == null ? List.of() : List.of(next.id()));
            }
        }
        Set<String> reachable = new HashSet<>();
        reachable.add(entry.id());
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String id : new HashSet<>(reachable)) {
                for (String next : successors.getOrDefault(id, List.of())) {
                    if (reachable.add(next)) {
                        changed = true;
                    }
                }
            }
        }
        for (StepDefinition s : steps) {
            if (s.action() != null && !reachable.contains(s.id())) {
                result.add(ValidationIssue.error("Step '" + s.id()
                        + "' is unreachable from the entry step", s.id()));
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private TemplateResolution resolveTemplate(CommunicationChannel channel, String code) {
        if (channel == CommunicationChannel.WHATSAPP) {
            return whatsappTemplates.findByCodeAndEnabledTrue(code)
                    .map(t -> new TemplateResolution(
                            normalize(t.getCategory()),
                            t.getApprovalStatus() == WhatsAppTemplate.ApprovalStatus.APPROVED))
                    .orElse(null);
        }
        return channelTemplates.findByChannelAndCode(channel, code)
                .map(t -> new TemplateResolution(
                        t.getCategory(),
                        t.getApprovalStatus() == ChannelTemplate.ApprovalStatus.APPROVED))
                .orElse(null);
    }

    private record TemplateResolution(Purpose category, boolean approved) {
    }

    private static Purpose normalize(WhatsAppTemplate.Category category) {
        return category == WhatsAppTemplate.Category.MARKETING ? Purpose.MARKETING : Purpose.TRANSACTIONAL;
    }

    private static boolean valueMatches(FieldDef def, Object value) {
        return switch (def.type()) {
            case TEXT -> value instanceof String;
            case NUMBER -> value instanceof Number
                    || (value instanceof String s && isDecimal(s));
            case BOOLEAN -> value instanceof Boolean
                    || "true".equals(value) || "false".equals(value);
            case DATE -> value instanceof String && isDateLike((String) value);
            case DATE_TIME -> value instanceof String && isDateTimeLike((String) value);
            case UUID -> value instanceof String && isUuid(value) && !((String) value).isBlank();
            case ENUM -> value instanceof String s && def.allowedValues().contains(s);
        };
    }

    private static boolean isDecimal(String s) {
        try {
            new BigDecimal(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isDateLike(String s) {
        String v = s.trim();
        if (v.equals("today") || v.equals("today+1") || v.equals("today-1")) {
            return true;
        }
        try {
            LocalDate.parse(v);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isDateTimeLike(String s) {
        String v = s.trim();
        if (v.equals("now") || v.equalsIgnoreCase("now")) {
            return true;
        }
        try {
            Instant.parse(v);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isUuid(Object value) {
        if (!(value instanceof String s)) {
            return false;
        }
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")
                || host.matches("^\\[[0-9a-fA-F:]+\\]$");
    }

    private static String stringOf(Object o) {
        return o instanceof String s ? s : null;
    }

    private static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

private static String describe(Object v) {
        return v == null ? "null" : "(" + v.getClass().getSimpleName() + ") " + v;
    }

    /** Lightweight six-field cron check: six space-separated numeric tokens. */
    private static boolean isSixFieldCron(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String[] tokens = expression.trim().split("\\s+");
        if (tokens.length != 6) {
            return false;
        }
        for (String t : tokens) {
            if (t.isBlank() || t.length() > 10 || !t.matches("^[0-9*?,\\-/]+$")) {
                return false;
            }
        }
        return true;
    }
}