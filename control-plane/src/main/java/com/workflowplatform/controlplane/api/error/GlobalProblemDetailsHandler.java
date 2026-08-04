package com.workflowplatform.controlplane.api.error;

import com.workflowplatform.contracts.problem.FieldValidationError;
import com.workflowplatform.contracts.problem.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public final class GlobalProblemDetailsHandler {

    private final ProblemDetailsResponseFactory responseFactory;

    public GlobalProblemDetailsHandler(ProblemDetailsResponseFactory responseFactory) {
        this.responseFactory = responseFactory;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
        return responseFactory.create(
                ProblemCode.COMMON_VALIDATION_INVALID_FIELD,
                request,
                FieldValidationErrorMapper.from(fieldErrors),
                null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleMalformedRequest(HttpServletRequest request) {
        return responseFactory.create(
                ProblemCode.COMMON_VALIDATION_MALFORMED_REQUEST, request, List.of(), null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> handleMissingParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        FieldValidationError error =
                FieldValidationErrorMapper.forField(exception.getParameterName(), "NotNull");
        return responseFactory.create(
                ProblemCode.COMMON_VALIDATION_MISSING_PARAMETER, request, List.of(error), null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException exception, HttpServletRequest request) {
        FieldValidationError error =
                FieldValidationErrorMapper.forField(exception.getHeaderName(), "NotNull");
        return responseFactory.create(
                ProblemCode.COMMON_VALIDATION_MISSING_PARAMETER, request, List.of(error), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        FieldValidationError error =
                FieldValidationErrorMapper.forField(exception.getName(), "typeMismatch");
        return responseFactory.create(
                ProblemCode.COMMON_VALIDATION_TYPE_MISMATCH, request, List.of(error), null);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleHandlerMethodValidation(HttpServletRequest request) {
        return responseFactory.create(
                ProblemCode.COMMON_VALIDATION_INVALID_FIELD, request, List.of(), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNoResource(HttpServletRequest request) {
        return responseFactory.create(
                ProblemCode.COMMON_NOT_FOUND_RESOURCE, request, List.of(), null);
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ProblemDetail> handleApplicationProblem(
            ApiProblemException exception, HttpServletRequest request) {
        return responseFactory.create(
                exception.problemCode(), request, List.of(), exception.retryAfterSeconds());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(HttpServletRequest request) {
        return responseFactory.create(
                ProblemCode.COMMON_INTERNAL_UNEXPECTED, request, List.of(), null);
    }
}
