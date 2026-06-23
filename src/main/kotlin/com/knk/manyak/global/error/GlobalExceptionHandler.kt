package com.knk.manyak.global.error

import com.knk.manyak.global.observability.ApiRequestLoggingFilter
import io.sentry.Sentry
import io.sentry.protocol.SentryId
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        exception: ResponseStatusException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        logResponseStatusException(exception, request, status)
        // 예상하지 못한 5xx만 Sentry로 보낸다. 예상 가능한 4xx(BAD_REQUEST/NOT_FOUND/CONFLICT 등)는 제외한다.
        if (status.is5xxServerError) {
            captureToSentry(exception, request, status, status.name)
        }
        return ResponseEntity
            .status(status)
            .body(
                ApiErrorResponse(
                    status = status.value(),
                    code = status.name,
                    message = exception.reason ?: status.reasonPhrase,
                    path = request.requestURI,
                ),
            )
    }

    private fun logResponseStatusException(
        exception: ResponseStatusException,
        request: HttpServletRequest,
        status: HttpStatus,
    ) {
        val message = "ResponseStatusException occurred: status={}, path={}, reason={}"
        val reason = exception.reason ?: status.reasonPhrase
        when {
            status.is5xxServerError -> log.error(message, status.value(), request.requestURI, reason, exception)
            exception.cause != null -> log.warn(message, status.value(), request.requestURI, reason, exception)
            else -> log.debug(message, status.value(), request.requestURI, reason)
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        badRequest(
            request = request,
            details = exception.bindingResult.fieldErrors.map { fieldError ->
                ApiErrorDetail(
                    field = fieldError.field,
                    message = fieldError.defaultMessage ?: "요청 값이 올바르지 않습니다.",
                )
            } + exception.bindingResult.globalErrors.map { objectError ->
                ApiErrorDetail(
                    field = objectError.objectName,
                    message = objectError.defaultMessage ?: "요청 값이 올바르지 않습니다.",
                )
            },
        )

    @ExceptionHandler(BindException::class)
    fun handleBindException(
        exception: BindException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        badRequest(
            request = request,
            details = exception.bindingResult.fieldErrors.map { fieldError ->
                ApiErrorDetail(
                    field = fieldError.field,
                    message = fieldError.defaultMessage ?: "요청 값이 올바르지 않습니다.",
                )
            },
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(
        exception: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.debug("Malformed request body: path={}, message={}", request.requestURI, exception.message)
        return badRequest(request = request, details = emptyList())
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolationException(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        badRequest(
            request = request,
            details = exception.constraintViolations.map { violation ->
                ApiErrorDetail(
                    field = violation.propertyPath?.toString(),
                    message = violation.message,
                )
            },
        )

    @ExceptionHandler(Exception::class)
    fun handleException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled exception occurred", exception)
        captureToSentry(exception, request, HttpStatus.INTERNAL_SERVER_ERROR, exception::class.simpleName)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ApiErrorResponse(
                    status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    code = HttpStatus.INTERNAL_SERVER_ERROR.name,
                    message = "서버 오류가 발생했습니다.",
                    path = request.requestURI,
                ),
            )
    }

    /**
     * 예상하지 못한 5xx 오류만 Sentry로 보낸다(4xx 핸들러에서는 호출하지 않는다).
     * endpoint/http_method/status_code/error_code를 scope tag로 싣고, request_id 등 MDC는 EventProcessor가 채운다.
     */
    private fun captureToSentry(
        exception: Throwable,
        request: HttpServletRequest,
        status: HttpStatus,
        errorCode: String?,
    ): SentryId {
        var sentryId = SentryId.EMPTY_ID
        Sentry.withScope { scope ->
            scope.setTag("endpoint", request.requestURI)
            scope.setTag("http_method", request.method)
            scope.setTag("status_code", status.value().toString())
            errorCode?.let { scope.setTag("error_code", it) }
            durationMs(request)?.let { scope.setContexts("timing", mapOf("duration_ms" to it)) }
            sentryId = Sentry.captureException(exception)
        }
        // 후속 api_request_failed 로그가 같은 요청의 sentry_event_id를 싣도록 MDC에 남긴다.
        MDC.put(SENTRY_EVENT_ID_MDC, sentryId.toString())
        return sentryId
    }

    private fun durationMs(request: HttpServletRequest): Long? =
        (request.getAttribute(ApiRequestLoggingFilter.REQUEST_START_NANOS_ATTRIBUTE) as? Long)
            ?.let { (System.nanoTime() - it) / 1_000_000 }

    private fun badRequest(
        request: HttpServletRequest,
        details: List<ApiErrorDetail>,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .badRequest()
            .body(
                ApiErrorResponse(
                    status = HttpStatus.BAD_REQUEST.value(),
                    code = HttpStatus.BAD_REQUEST.name,
                    message = "요청 값이 올바르지 않습니다.",
                    path = request.requestURI,
                    details = details,
                ),
            )

    private companion object {
        const val SENTRY_EVENT_ID_MDC = "sentry_event_id"
    }
}
