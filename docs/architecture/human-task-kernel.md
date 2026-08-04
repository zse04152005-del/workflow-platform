# Human-task kernel

Creating a task returns the node transition target `WAITING_APPROVAL`; the JDBC
adapter persists both in one transaction. Candidate roles and optional assignee
drive the inbox. Approval/rejection requires OPEN plus expected version, so only
the first concurrent decision succeeds. Transfers append actor/reason history.

Each node selects fail, auto-reject or escalation reminder on timeout. Form
fields pass through the common audit redactor before they enter action history.
The automated refund scenario verifies role visibility, redaction, approval and
the rejection of a late second decision.
