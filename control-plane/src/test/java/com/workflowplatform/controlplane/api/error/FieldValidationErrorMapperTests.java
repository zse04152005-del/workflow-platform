package com.workflowplatform.controlplane.api.error;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workflowplatform.contracts.problem.FieldValidationError;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.validation.FieldError;

class FieldValidationErrorMapperTests {

    @Test
    void mapsFrameworkCodesToAStableCuratedCatalog() {
        List<FieldError> source =
                List.of(
                        error("z", "NotBlank"),
                        error("email", "Email"),
                        error("items[0].name", "Size"),
                        error("count", "Min"),
                        error("amount", "typeMismatch"),
                        error("odd", "FutureFrameworkCode"),
                        error("z", "NotBlank"));

        List<FieldValidationError> mapped = FieldValidationErrorMapper.from(source);

        assertEquals(
                List.of(
                        "amount:invalid_type:value has the wrong type",
                        "count:out_of_range:value is outside the allowed range",
                        "email:invalid_format:format is invalid",
                        "items[0].name:invalid_size:size is outside the allowed range",
                        "odd:invalid:value is invalid",
                        "z:required:value is required"),
                mapped.stream()
                        .map(error -> error.field() + ":" + error.code() + ":" + error.message())
                        .toList(),
                "Framework constraints must map to stable sorted public errors");
    }

    @Test
    void sanitizesUnsafeFieldNamesAndLimitsTheResponse() {
        ArrayList<FieldError> errors = new ArrayList<>();
        for (int index = 0; index < 55; index += 1) {
            errors.add(error("field" + index, "NotNull"));
        }
        errors.add(error("../../password", "Pattern"));

        List<FieldValidationError> mapped = FieldValidationErrorMapper.from(errors);

        assertEquals(
                FieldValidationErrorMapper.MAX_ERRORS,
                mapped.size(),
                "Field errors must be bounded");
        assertEquals(
                "_request",
                FieldValidationErrorMapper.forField("../../password", "Pattern").field(),
                "Unsafe field paths must be replaced");
    }

    private static FieldError error(String field, String code) {
        return new FieldError("request", field, null, false, new String[] {code}, null, "unsafe");
    }
}
