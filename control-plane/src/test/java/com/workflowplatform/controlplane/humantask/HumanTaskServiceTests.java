package com.workflowplatform.controlplane.humantask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class HumanTaskServiceTests {

    private static final UUID TASK_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");
    private static final UUID NODE_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c494");

    @Test
    void refundApprovalAcceptsOnlyFirstDecisionAndRedactsForm() {
        HumanTaskService service = new HumanTaskService();
        HumanTaskService.Creation creation =
                service.create(
                        new HumanTaskService.CreateCommand(
                                TASK_ID,
                                NODE_ID,
                                Set.of("finance-approver"),
                                null,
                                Instant.parse("2026-07-31T01:00:00Z"),
                                HumanTaskService.TimeoutPolicy.AUTO_REJECT,
                                Map.of("refundAmount", 50000, "token", "do-not-store")));
        assertEquals("WAITING_APPROVAL", creation.nodeStatus());
        assertEquals("<redacted>", creation.task().formRedacted().get("token"));
        assertEquals(1, service.inbox("finance-user", Set.of("finance-approver"), 20).size());

        HumanTaskService.HumanTask approved =
                service.decide(
                        TASK_ID,
                        0,
                        HumanTaskService.Decision.APPROVE,
                        "finance-user",
                        "verified refund evidence");
        assertEquals(HumanTaskService.Status.APPROVED, approved.status());
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.decide(
                                TASK_ID,
                                0,
                                HumanTaskService.Decision.REJECT,
                                "other-user",
                                "late decision"));
    }

    @Test
    void transferAndTimeoutPoliciesAreExplicit() {
        HumanTaskService service = new HumanTaskService();
        service.create(
                new HumanTaskService.CreateCommand(
                        TASK_ID,
                        NODE_ID,
                        Set.of("approver"),
                        null,
                        Instant.EPOCH,
                        HumanTaskService.TimeoutPolicy.ESCALATE,
                        Map.of("amount", 1)));
        assertEquals(
                "approver-user",
                service.transfer(TASK_ID, 0, "lead", "approver-user", "shift handoff").assignee());
        assertEquals(
                HumanTaskService.TimeoutAction.ESCALATE_REMINDER,
                service.timeout(TASK_ID, Instant.EPOCH.plusSeconds(1)).action());
    }
}
