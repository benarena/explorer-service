package io.provenance.explorer.config

import io.provenance.explorer.domain.exceptions.TendermintApiException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import javax.servlet.http.HttpServletRequest

@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class GlobalControllerAdvice : ResponseEntityExceptionHandler() {

    @ExceptionHandler(TendermintApiException::class)
    fun endpointExceptionHandler(ex: TendermintApiException): ResponseEntity<Any> =
        ResponseEntity<Any>(ex.message, HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR);

    /**
     * Catch ResourceNotFoundException, return 404
     */
    @ExceptionHandler(ResourceNotFoundException::class)
    fun handleNotFound(exception: ResourceNotFoundException, request: HttpServletRequest): ResponseEntity<Any> {
        logger.debug("404 on '${request.requestURI}' with error '${exception.message}'")
        return ResponseEntity.notFound().build()
    }
}

open class ResourceNotFoundException(message: String? = "", cause: Throwable? = null) : RuntimeException(message, cause)
