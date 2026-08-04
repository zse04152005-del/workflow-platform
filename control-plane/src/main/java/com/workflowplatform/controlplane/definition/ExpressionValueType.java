package com.workflowplatform.controlplane.definition;

/** Bounded static value types used by the workflow expression compiler. */
public enum ExpressionValueType {
    NULL,
    BOOLEAN,
    INTEGER,
    NUMBER,
    STRING,
    OBJECT,
    ARRAY,
    DYNAMIC
}
