# Baseline threat model

## Assets and trust boundaries

Assets are tenant workflow definitions, runtime state, connector/secret
references, audit history and operational credentials. Boundaries exist at the
browser/API, OIDC issuer/API, API/PostgreSQL, Outbox/Kafka/Worker, Worker/managed
HTTP endpoint and application/KMS-object-storage interfaces.

## STRIDE risks and mitigations

| Risk | Example | Mandatory mitigation |
|---|---|---|
| Spoofing | forged tenant token | issuer/audience/expiry/signature validation |
| Tampering | stale worker result | status/version plus lease generation fencing |
| Repudiation | untracked admin action | append-only actor/IP/reason/trace audit |
| Information disclosure | Secret in payload/log | opaque references, AES/KMS provider, redaction and scans |
| Denial of service | payload bomb or retry storm | size/time/concurrency/rate limits and bounded retries |
| Elevation of privilege | tenant requests platform admin | separate non-inheritable role and forced RLS |

SSRF is treated as both disclosure and elevation: HTTPS/host/port are checked,
all DNS answers and every redirect are revalidated, private/link-local/loopback
addresses are denied and response bytes are capped.
