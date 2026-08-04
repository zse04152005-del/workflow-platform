# ADR-005 OIDC, tenant context, RBAC, RLS and platform admin boundary

Status: Accepted — 2026-07-31

The authentication boundary accepts only issuer-pinned, audience-matching,
unexpired OIDC tokens whose signature is verified by the configured JWKS
provider. Tenant and actor context come from verified claims, never request
headers. Project membership grants the minimum explicit permission set.

Every repository transaction sets a tenant-local PostgreSQL context before any
query. Forced RLS remains enabled for business tables. Platform maintenance is
a separate audited database role and API surface; tenant users cannot request
or inherit it. Background messages carry tenant ID in the signed/event envelope
and re-establish the same context.

Secret values are resolved by provider from opaque references. Development may
use caller-supplied AES-GCM keys; production uses a KMS/Vault provider. Outbound
HTTP validates scheme, port, DNS results, each redirect, IP class and response
size. Limits and audit records are tenant-scoped and never place tenant/workflow
IDs in high-cardinality metrics.
