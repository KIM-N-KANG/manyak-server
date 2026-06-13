package com.knk.manyak.global.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
}
