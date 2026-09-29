package com.securetravels.crm.automation.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and validates the {@link ActionType#BRANCH} step config.
 *
 * <pre>
 * {
 *   "cases":  [ { "when": {condition tree}, "goto": "stepId" }, ... ],
 *   "default": "stepId"                      // optional
 * }
 * </pre>
 * The first case whose guard passes wins; otherwise the default runs; with no
 * default the BRANCH terminates the run.
 */
public final class BranchConfig {

    private BranchConfig() {
    }

    public static List<String> targets(Map<String, Object> config) {
        List<String> targets = new ArrayList<>();
        if (config == null) {
            return List.of();
        }
        Object casesRaw = config.get("cases");
        if (casesRaw instanceof List<?> cases) {
            for (Object c : cases) {
                if (c instanceof Map<?, ?> caseMap) {
                    Object gotoRaw = caseMap.get("goto");
                    if (gotoRaw instanceof String g) {
                        targets.add(g);
                    }
                }
            }
        }
        if (config.get("default") instanceof String d) {
            targets.add(d);
        }
        return targets;
    }
}