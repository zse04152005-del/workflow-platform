package com.workflowplatform.controlplane.runtime;

import java.util.Map;

/** Safe baseline plugins for Start/End and deterministic Delay/Condition execution. */
public final class BuiltinNodePlugins {

    private BuiltinNodePlugins() {}

    public static NodePlugin start() {
        return identity("start");
    }

    public static NodePlugin end() {
        return identity("end");
    }

    public static NodePlugin delay() {
        return new NodePlugin() {
            @Override
            public String type() {
                return "delay";
            }

            @Override
            public NodeExecutionResult execute(NodeExecutionContext context) {
                if (context.timeout().isNegative() || context.timeout().isZero()) {
                    return NodeExecutionResult.failure(
                            "NODE_TIMEOUT", "delay timeout is non-positive", true);
                }
                return NodeExecutionResult.success(Map.of("scheduled", true));
            }
        };
    }

    public static NodePlugin condition() {
        return new NodePlugin() {
            @Override
            public String type() {
                return "condition";
            }

            @Override
            public NodeExecutionResult execute(NodeExecutionContext context) {
                Object value = context.input().get("condition");
                if (!(value instanceof Boolean)) {
                    return NodeExecutionResult.failure(
                            "CONDITION_NOT_BOOLEAN", "condition result is not boolean", false);
                }
                return NodeExecutionResult.success(Map.of("branch", value));
            }
        };
    }

    private static NodePlugin identity(String type) {
        return new NodePlugin() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public NodeExecutionResult execute(NodeExecutionContext context) {
                return NodeExecutionResult.success(context.input());
            }
        };
    }
}
