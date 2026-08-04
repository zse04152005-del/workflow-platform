package com.workflowplatform.controlplane.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Provider-neutral OIDC validation boundary for signature and mandatory claims. */
public final class OidcTokenValidator {

    private final String issuer;
    private final String audience;
    private final SignatureVerifier signatureVerifier;

    public OidcTokenValidator(
            String trustedIssuer, String requiredAudience, SignatureVerifier verifier) {
        issuer = Objects.requireNonNull(trustedIssuer, "trustedIssuer");
        audience = Objects.requireNonNull(requiredAudience, "requiredAudience");
        signatureVerifier = Objects.requireNonNull(verifier, "verifier");
    }

    public WorkflowRequestContext validate(String compactToken, Claims claims, Instant now) {
        if (!signatureVerifier.verify(compactToken)
                || !issuer.equals(claims.issuer())
                || !claims.audiences().contains(audience)
                || !claims.expiresAt().isAfter(now)
                || claims.notBefore().isAfter(now)) {
            throw new SecurityException("OIDC token validation failed");
        }
        return new WorkflowRequestContext(claims.tenantId(), claims.subject());
    }

    @FunctionalInterface
    public interface SignatureVerifier {
        boolean verify(String compactToken);
    }

    public record Claims(
            String issuer,
            Set<String> audiences,
            Instant expiresAt,
            Instant notBefore,
            String subject,
            UUID tenantId) {
        public Claims {
            audiences = Set.copyOf(audiences);
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(notBefore, "notBefore");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }
}
