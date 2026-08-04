package com.workflowplatform.controlplane.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class StrongDraftEtagTests {

    private static final UUID WORKFLOW_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final String CHECKSUM = "a".repeat(64);

    @Test
    void roundTripsAQuotedStrongRevisionToken() {
        WorkflowDraft draft = draft(7, CHECKSUM);

        String etag = StrongDraftEtag.from(draft);
        StrongDraftEtag.RevisionToken parsed = StrongDraftEtag.parseIfMatch(etag);

        assertEquals(WORKFLOW_ID, parsed.workflowId());
        assertEquals(7, parsed.revision());
        assertEquals(CHECKSUM, parsed.checksum());
        assertTrue(etag.startsWith("\"wf-draft:"));
    }

    @Test
    void distinguishesMissingFromMalformedWeakWildcardAndLists() {
        StrongDraftEtagException missing =
                assertThrows(
                        StrongDraftEtagException.class, () -> StrongDraftEtag.parseIfMatch(null));
        assertEquals(StrongDraftEtagException.Failure.REQUIRED, missing.failure());

        for (String invalid :
                new String[] {
                    "*",
                    "W/\"wf-draft:" + WORKFLOW_ID + ":1:" + CHECKSUM + "\"",
                    "\"wf-draft:" + WORKFLOW_ID + ":1:" + CHECKSUM + "\", \"other\"",
                    "\"wf-draft:" + WORKFLOW_ID + ":0:" + CHECKSUM + "\""
                }) {
            StrongDraftEtagException malformed =
                    assertThrows(
                            StrongDraftEtagException.class,
                            () -> StrongDraftEtag.parseIfMatch(invalid));
            assertEquals(StrongDraftEtagException.Failure.INVALID, malformed.failure());
        }
    }

    private static WorkflowDraft draft(long revision, String checksum) {
        return new WorkflowDraft(
                WORKFLOW_ID,
                UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494"),
                "etag-test",
                null,
                "DRAFT",
                revision,
                null,
                "{}",
                checksum,
                Instant.parse("2026-07-31T00:00:00Z"),
                Instant.parse("2026-07-31T00:00:00Z"));
    }
}
