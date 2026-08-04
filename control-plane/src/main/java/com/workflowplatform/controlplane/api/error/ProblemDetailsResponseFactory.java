package com.workflowplatform.controlplane.api.error;

import com.workflowplatform.contracts.problem.FieldValidationError;
import com.workflowplatform.contracts.problem.ProblemCode;
import com.workflowplatform.contracts.problem.ProblemDescriptor;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public final class ProblemDetailsResponseFactory {

    static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String TRACEPARENT_HEADER = "traceparent";
    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    public ResponseEntity<ProblemDetail> create(
            ProblemCode code,
            HttpServletRequest request,
            List<FieldValidationError> fieldErrors,
            Integer retryAfterSeconds) {
        ProblemDescriptor descriptor = code.descriptor();
        List<FieldValidationError> errors = List.copyOf(fieldErrors);
        if (errors.size() > FieldValidationErrorMapper.MAX_ERRORS) {
            throw new IllegalArgumentException("At most 50 field errors may be returned");
        }
        String traceId =
                TraceIdResolver.resolve(
                        request.getHeader(TRACEPARENT_HEADER),
                        request.getHeader(CORRELATION_ID_HEADER));

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatusCode.valueOf(descriptor.status()), descriptor.defaultDetail());
        problem.setType(descriptor.type());
        problem.setTitle(descriptor.title());
        problem.setInstance(requestPath(request));
        problem.setProperty("code", descriptor.code());
        problem.setProperty("traceId", traceId);
        problem.setProperty("errors", errors);
        problem.setProperty("retryable", descriptor.retryable());
        if (retryAfterSeconds != null) {
            problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.setCacheControl(CacheControl.noStore());
        headers.set(TRACE_ID_HEADER, traceId);
        if (descriptor.status() == HttpStatus.UNAUTHORIZED.value()) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        if (retryAfterSeconds != null) {
            headers.set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString());
        }
        return new ResponseEntity<>(problem, headers, descriptor.status());
    }

    private static URI requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return URI.create(path == null || path.isBlank() ? "/" : path);
    }
}
