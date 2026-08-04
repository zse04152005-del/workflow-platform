# G6 authentication, tenant security and governance verification

## Status

`[~]` ADR, production security components, STRIDE model and negative unit cases
are implemented. Keycloak/Testcontainers and final supply-chain commands await
the consolidated external-tool run.

## Evidence

- OIDC issuer/audience/time/signature validation and trusted tenant context;
- minimum project RBAC, forced RLS contract and separate admin boundary;
- AES-GCM development Secret provider plus KMS/Vault port;
- HTTPS/host/port/DNS/IP/redirect/response-size SSRF policy;
- tenant rate/concurrency/backpressure, append-only audit redaction;
- dependency/Secret/SBOM/security-header supply-chain contract and STRIDE model.
