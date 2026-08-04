package com.workflowplatform.controlplane.definition;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/** Version-bound result returned by the Validate API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowValidationResult(
        String source,
        Long draftRevision,
        String checksum,
        boolean valid,
        int issueCount,
        boolean truncated,
        List<WorkflowValidationIssue> issues) {

    public WorkflowValidationResult {
        source = Objects.requireNonNull(source, "source");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (!"current_draft".equals(source) && !"supplied_definition".equals(source)) {
            throw new IllegalArgumentException("Unsupported validation source");
        }
        if (("current_draft".equals(source)) != (draftRevision != null)) {
            throw new IllegalArgumentException("Only current draft results expose a revision");
        }
        if (draftRevision != null && draftRevision < 1) {
            throw new IllegalArgumentException("draftRevision must be positive");
        }
        if (checksum != null && !checksum.matches("^[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("checksum must be SHA-256 when present");
        }
        if (issueCount < issues.size() || issueCount < 0) {
            throw new IllegalArgumentException("issueCount must include every returned issue");
        }
        if (valid != (issueCount == 0) || truncated != (issueCount > issues.size())) {
            throw new IllegalArgumentException("Validation result counters are inconsistent");
        }
    }
}
