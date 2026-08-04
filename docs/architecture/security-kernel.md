# Security kernel

The security kernel implements ADR-005 and keeps identity, tenant isolation,
secrets, outbound policy and audit concerns independent from workflow business
logic. Keycloak is the local OIDC issuer; the validation boundary remains
provider-neutral. Repository code sets tenant context transaction-locally and
the 22 business tables use forced RLS.

Development encryption takes the AES key from configuration, never source.
Production integrates through the same `SecretProvider` port with KMS/Vault.
`OutboundRequestPolicy` validates DNS/IP and redirects at every hop. Tenant
quota limits and audit redaction are deterministic and unit tested. CI combines
dependency scanning, gitleaks, SBOM generation and image security headers.
