package com.workflowplatform.contracts.problem;

import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable public metadata for one stable Problem Details error code. */
public record ProblemDescriptor(
        String code, URI type, String title, int status, String defaultDetail, boolean retryable)
        implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    private static final Pattern ERROR_CODE = Pattern.compile("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+){2,}$");

    public ProblemDescriptor {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(defaultDetail, "defaultDetail");
        if (!ERROR_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid stable error code");
        }
        if (!type.isAbsolute() || !"https".equalsIgnoreCase(type.getScheme())) {
            throw new IllegalArgumentException("Problem type must be an absolute HTTPS URI");
        }
        if (title.isBlank() || defaultDetail.isBlank()) {
            throw new IllegalArgumentException(
                    "Problem title and default detail must not be blank");
        }
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Problem status must be an HTTP error status");
        }
    }
}
