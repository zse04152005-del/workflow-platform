package com.workflowplatform.controlplane.definition;

import java.util.Objects;

/** Fully revalidated current draft and typed configuration ready for atomic publication. */
public record WorkflowPublicationCandidate(
        WorkflowDraft draft,
        ParsedWorkflowDefinition parsed,
        ValidatedWorkflowConfiguration configuration) {

    public WorkflowPublicationCandidate {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(parsed, "parsed");
        Objects.requireNonNull(configuration, "configuration");
        if (!draft.name().equals(parsed.definition().name())
                || !draft.draftChecksum().equals(parsed.checksum())
                || !parsed.definition().equals(configuration.graph().definition())) {
            throw new IllegalStateException(
                    "Publication candidate does not match the locked draft snapshot");
        }
    }
}
