package org.urizo.axmodulestudio.backend.cms.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.urizo.axmodulestudio.backend.cms.service.CmsServiceException;

@RestControllerAdvice(basePackageClasses = CmsAdminController.class)
@Profile("local-full")
public class CmsExceptionHandler {

    @ExceptionHandler(CmsServiceException.class)
    ProblemDetail handle(CmsServiceException failure) {
        HttpStatus status = failure.kind() == CmsServiceException.Kind.NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return ProblemDetail.forStatusAndDetail(status, failure.getMessage());
    }
}
