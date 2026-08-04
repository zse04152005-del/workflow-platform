package com.workflowplatform.controlplane.definition;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict strong ETag codec bound to workflow identity, draft revision and canonical checksum. */
public final class StrongDraftEtag {

    private static final Pattern VALUE_PATTERN =
            Pattern.compile(
                    "^\\\"wf-draft:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                            + "[0-9a-f]{4}-[0-9a-f]{12}):([1-9][0-9]*):([0-9a-f]{64})\\\"$");

    private StrongDraftEtag() {}

    public static String from(WorkflowDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return format(new RevisionToken(draft.id(), draft.draftRevision(), draft.draftChecksum()));
    }

    public static String format(RevisionToken token) {
        Objects.requireNonNull(token, "token");
        return "\"wf-draft:"
                + token.workflowId()
                + ':'
                + token.revision()
                + ':'
                + token.checksum()
                + "\"";
    }

    public static RevisionToken parseIfMatch(String value) {
        if (value == null || value.isBlank()) {
            throw new StrongDraftEtagException(StrongDraftEtagException.Failure.REQUIRED);
        }
        Matcher matcher = VALUE_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new StrongDraftEtagException(StrongDraftEtagException.Failure.INVALID);
        }
        try {
            return new RevisionToken(
                    UUID.fromString(matcher.group(1)),
                    Long.parseLong(matcher.group(2)),
                    matcher.group(3));
        } catch (IllegalArgumentException exception) {
            throw new StrongDraftEtagException(StrongDraftEtagException.Failure.INVALID, exception);
        }
    }

    /** Parsed immutable revision identity. */
    public record RevisionToken(UUID workflowId, long revision, String checksum) {

        public RevisionToken {
            Objects.requireNonNull(workflowId, "workflowId");
            checksum = Objects.requireNonNull(checksum, "checksum");
            if (revision < 1 || !checksum.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid draft revision token");
            }
        }
    }
}
