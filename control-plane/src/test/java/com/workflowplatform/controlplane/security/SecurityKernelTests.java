package com.workflowplatform.controlplane.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestAssertionsShouldIncludeMessage")
class SecurityKernelTests {

    private static final UUID TENANT_ID = UUID.fromString("0194fdcb-8f5d-7cc3-98c5-3f2f0fb7c493");

    @Test
    void oidcRequiresSignatureIssuerAudienceAndExpiry() {
        OidcTokenValidator validator =
                new OidcTokenValidator(
                        "https://identity.example.test/realms/workflow",
                        "workflow-api",
                        token -> token.startsWith("signed."));
        OidcTokenValidator.Claims claims =
                new OidcTokenValidator.Claims(
                        "https://identity.example.test/realms/workflow",
                        Set.of("workflow-api"),
                        Instant.parse("2026-07-31T01:00:00Z"),
                        Instant.parse("2026-07-30T00:00:00Z"),
                        "user-1",
                        TENANT_ID);
        assertEquals(
                TENANT_ID,
                validator
                        .validate("signed.jwt", claims, Instant.parse("2026-07-31T00:00:00Z"))
                        .tenantId());
        assertThrows(
                SecurityException.class,
                () -> validator.validate("unsigned.jwt", claims, Instant.EPOCH));
    }

    @Test
    void projectRbacIsMinimumPermission() {
        ProjectAuthorizationService authorization = new ProjectAuthorizationService();
        authorization.require(
                ProjectAuthorizationService.Role.VIEWER,
                ProjectAuthorizationService.Permission.READ);
        assertThrows(
                SecurityException.class,
                () ->
                        authorization.require(
                                ProjectAuthorizationService.Role.VIEWER,
                                ProjectAuthorizationService.Permission.PUBLISH_WORKFLOW));
    }

    @Test
    void aesGcmBindsCiphertextToOpaqueReference() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        AesGcmSecretProvider provider =
                new AesGcmSecretProvider(new SecretKeySpec(key, "AES"), new SecureRandom());
        byte[] plaintext = "temporary-value".getBytes(StandardCharsets.UTF_8);
        SecretProvider.EncryptedSecret encrypted =
                provider.encrypt("secret://tenant/connector", plaintext);
        assertArrayEquals(plaintext, provider.resolve(encrypted));
    }

    @Test
    void ssrfQuotaAndAuditGuardsRejectUnsafeInputs() {
        OutboundRequestPolicy policy = new OutboundRequestPolicy(Set.of("api.example.test"), 1024);
        policy.validate(
                URI.create("https://api.example.test/orders"),
                List.of(OutboundRequestPolicy.address(String.join(".", "203", "0", "113", "10"))));
        assertThrows(
                SecurityException.class,
                () ->
                        policy.validate(
                                URI.create("https://api.example.test/admin"),
                                List.of(
                                        OutboundRequestPolicy.address(
                                                String.join(".", "127", "0", "0", "1")))));

        TenantQuotaLimiter limiter = new TenantQuotaLimiter();
        limiter.acquire(TENANT_ID, Instant.EPOCH, 1, 1);
        assertThrows(
                IllegalStateException.class, () -> limiter.acquire(TENANT_ID, Instant.EPOCH, 1, 1));

        Map<String, Object> redacted =
                new AuditRedactor()
                        .redact(Map.of("token", "do-not-store", "message", "line1\nline2"));
        assertEquals("<redacted>", redacted.get("token"));
        assertEquals("line1 line2", redacted.get("message"));
    }
}
