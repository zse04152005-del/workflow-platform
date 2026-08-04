package com.workflowplatform.controlplane.definition;

import java.util.Objects;
import java.util.regex.Pattern;

/** Shared validation rules for workflow-draft persistence values. */
final class WorkflowDraftValidation {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private WorkflowDraftValidation() {}

    static String requireDigest(String value, String label) {
        String digest = Objects.requireNonNull(value, label);
        if (!SHA_256.matcher(digest).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 digest");
        }
        return digest;
    }
}
