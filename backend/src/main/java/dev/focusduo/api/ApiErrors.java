package dev.focusduo.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    private final ObjectMapper json;

    public ApiErrors(ObjectMapper json) { this.json = json; }

    public Api.Error error(ApiException exception, HttpServletRequest request) {
        return new Api.Error(exception.code(), exception.getMessage(), RequestIdFilter.requestId(request),
                exception.fieldErrors(), exception.snapshot());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ApiException exception) throws IOException {
        response.setStatus(exception.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), error(exception, request));
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Api.Error> expected(ApiException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception, request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Api.Error> beanValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return expected(new ApiException(400, "VALIDATION_ERROR", "Request validation failed", fields, null), request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Api.Error> missingHeader(MissingRequestHeaderException exception, HttpServletRequest request) {
        return expected(ApiException.validation(exception.getHeaderName(), "Required header is missing"), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Api.Error> missingParameter(MissingServletRequestParameterException exception, HttpServletRequest request) {
        return expected(ApiException.validation(exception.getParameterName(), "Required parameter is missing"), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Api.Error> invalidType(MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return expected(ApiException.validation(exception.getName(), "Value has an invalid format"), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, ConstraintViolationException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Api.Error> malformed(Exception exception, HttpServletRequest request) {
        return expected(ApiException.validation("body", "A valid JSON request with the documented field types is required"), request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<Api.Error> notFound(Exception exception, HttpServletRequest request) {
        return expected(ApiException.notFound(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Api.Error> forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return expected(ApiException.forbidden(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Api.Error> unexpected(Exception exception, HttpServletRequest request) {
        // Exception messages can include submitted values or JDBC parameters: do not log them.
        log.error("Unhandled request failure requestId={} exceptionType={}", RequestIdFilter.requestId(request),
                exception.getClass().getName());
        return expected(new ApiException(500, "INTERNAL_ERROR", "An unexpected server error occurred"), request);
    }
}
