package org.urizo.axmodulestudio.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.urizo.axmodulestudio.backend.auth.service.AuthenticationFailedException;
import org.urizo.axmodulestudio.backend.core.web.TraceIdFilter;

class AuthExceptionHandlerTest {

    @Test
    void refreshFailureDoesNotClearPossiblyNewerRefreshCookie() {
        AuthExceptionHandler handler = new AuthExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/auth/refresh");
        request.setAttribute(TraceIdFilter.REQUEST_ATTRIBUTE, UUID.randomUUID().toString());

        var response = handler.authenticationFailure(
                new AuthenticationFailedException("stale refresh token"), request);

        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNullOrEmpty();
    }
}
