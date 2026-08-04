package com.workflowplatform.controlplane.definition;

import com.workflowplatform.contracts.dsl.WorkflowDefinition;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable result of parsing and canonicalizing one workflow definition source document. */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class ParsedWorkflowDefinition {

    private static final int MINIMUM_SOURCE_BYTES = 1;
    private static final String CHECKSUM_ALGORITHM = "SHA-256";

    private final WorkflowDefinitionSourceFormat sourceFormat;
    private final int sourceByteLength;
    private final WorkflowDefinition definition;
    private final String canonicalJson;
    private final String checksum;

    ParsedWorkflowDefinition(
            WorkflowDefinitionSourceFormat sourceFormat,
            int sourceByteLength,
            WorkflowDefinition definition,
            String canonicalJson) {
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat");
        if (sourceByteLength < MINIMUM_SOURCE_BYTES) {
            throw new IllegalArgumentException("sourceByteLength must be positive");
        }
        this.sourceByteLength = sourceByteLength;
        this.definition = Objects.requireNonNull(definition, "definition");
        this.canonicalJson = Objects.requireNonNull(canonicalJson, "canonicalJson");
        if (canonicalJson.isEmpty()) {
            throw new IllegalArgumentException("canonicalJson must not be empty");
        }
        this.checksum = sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
    }

    public WorkflowDefinitionSourceFormat sourceFormat() {
        return sourceFormat;
    }

    public int sourceByteLength() {
        return sourceByteLength;
    }

    public WorkflowDefinition definition() {
        return definition;
    }

    public String canonicalJson() {
        return canonicalJson;
    }

    public byte[] canonicalBytes() {
        return canonicalJson.getBytes(StandardCharsets.UTF_8);
    }

    public String checksum() {
        return checksum;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance(CHECKSUM_ALGORITHM).digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Required SHA-256 algorithm is unavailable", exception);
        }
    }
}
