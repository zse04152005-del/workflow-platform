package com.workflowplatform.controlplane.api.error;

import com.workflowplatform.contracts.problem.FieldValidationError;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.validation.FieldError;

final class FieldValidationErrorMapper {

    static final int MAX_ERRORS = 50;

    private static final Pattern SAFE_FIELD =
            Pattern.compile(
                    "^(?:_request|[A-Za-z][A-Za-z0-9_]*(?:\\[(?:0|[1-9][0-9]*)\\]|\\.[A-Za-z][A-Za-z0-9_]*)*)$");
    private static final Set<String> REQUIRED = Set.of("NotBlank", "NotEmpty", "NotNull");
    private static final Set<String> FORMAT = Set.of("Email", "Pattern");
    private static final Set<String> SIZE = Set.of("Size");
    private static final Set<String> RANGE =
            Set.of(
                    "DecimalMax",
                    "DecimalMin",
                    "Max",
                    "Min",
                    "Negative",
                    "NegativeOrZero",
                    "Positive",
                    "PositiveOrZero");
    private static final String TYPE_MISMATCH = "typeMismatch";

    private FieldValidationErrorMapper() {}

    static List<FieldValidationError> from(List<FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(FieldValidationErrorMapper::from)
                .sorted(
                        Comparator.comparing(FieldValidationError::field)
                                .thenComparing(FieldValidationError::code)
                                .thenComparing(FieldValidationError::message))
                .distinct()
                .limit(MAX_ERRORS)
                .toList();
    }

    static FieldValidationError forField(String field, String code) {
        return new FieldValidationError(safeField(field), safeCode(code), safeMessage(code));
    }

    private static FieldValidationError from(FieldError error) {
        return forField(error.getField(), error.getCode());
    }

    private static String safeField(String field) {
        return field != null && SAFE_FIELD.matcher(field).matches() ? field : "_request";
    }

    private static String safeCode(String frameworkCode) {
        if (REQUIRED.contains(frameworkCode)) {
            return "required";
        }
        if (FORMAT.contains(frameworkCode)) {
            return "invalid_format";
        }
        if (SIZE.contains(frameworkCode)) {
            return "invalid_size";
        }
        if (RANGE.contains(frameworkCode)) {
            return "out_of_range";
        }
        if (TYPE_MISMATCH.equals(frameworkCode)) {
            return "invalid_type";
        }
        return "invalid";
    }

    private static String safeMessage(String frameworkCode) {
        return switch (safeCode(frameworkCode)) {
            case "required" -> "value is required";
            case "invalid_format" -> "format is invalid";
            case "invalid_size" -> "size is outside the allowed range";
            case "out_of_range" -> "value is outside the allowed range";
            case "invalid_type" -> "value has the wrong type";
            default -> "value is invalid";
        };
    }
}
