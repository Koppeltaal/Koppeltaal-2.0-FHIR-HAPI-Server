/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.Oauth2AccessTokenService;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 *
 */
@Interceptor
public class JwtSecurityInterceptor {

	private static final Logger LOG = LoggerFactory.getLogger(JwtSecurityInterceptor.class);

	/**
	 * Request attribute with the access token once it passed the validation. Authorization decisions only use this
	 * token: a token in the header of a request on a public path (e.g. <code>/metadata</code>) is otherwise not
	 * validated and could be forged.
	 */
	public static final String VALIDATED_ACCESS_TOKEN_ATTRIBUTE = JwtSecurityInterceptor.class.getName() + ".validatedAccessToken";

	private final Oauth2AccessTokenService oauth2AccessTokenService;

	public JwtSecurityInterceptor(Oauth2AccessTokenService oauth2AccessTokenService) {
		this.oauth2AccessTokenService = oauth2AccessTokenService;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
	public void incomingRequestPreProcessed(HttpServletRequest request) {

		final String requestURI = request.getRequestURI();
		final String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(request.getHeader("Authorization"), "Bearer"));

		if (requiresBearerToken(request)) {
			try {
				if (StringUtils.isEmpty(token)) {
					LOG.warn("No access token found on URI {}.", requestURI);
					throw new AuthenticationException(HttpStatus.UNAUTHORIZED.getReasonPhrase());
				}
				if (!oauth2AccessTokenService.validateToken(token)) {
					LOG.warn("Invalid token {} on URI {}.", token, requestURI);
					throw new AuthenticationException(HttpStatus.UNAUTHORIZED.getReasonPhrase());
				}
			} catch (JWTVerificationException e) {
				LOG.warn(String.format("JWTVerificationException on URI %s.", requestURI), e);
				throw new AuthenticationException(HttpStatus.UNAUTHORIZED.getReasonPhrase());
			}
			request.setAttribute(VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);
		} else if (StringUtils.isNotEmpty(token) && isValid(token)) {
			// a public path needs no token, but a valid one still identifies the requester, e.g. in the AuditEvent
			request.setAttribute(VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);
		}
	}

	private boolean isValid(String token) {
		try {
			return oauth2AccessTokenService.validateToken(token);
		} catch (JWTVerificationException e) {
			return false;
		}
	}

	private boolean requiresBearerToken(HttpServletRequest request) {
		final String requestURI = request.getRequestURI();

		if (!("GET".equals(request.getMethod()))) {
			return true;
		}

		return StringUtils.startsWith(requestURI, "/fhir")
				&& !requestURI.matches("^/fhir/[a-zA-Z0-9_-]+/metadata.*") // allow multi-tenant /metadata requests
				&& !requestURI.matches("^/fhir/[a-zA-Z0-9_-]+/ImplementationGuide.*") // allow ImplementationGuides
				&& !requestURI.matches("^/fhir//?(?:swagger-ui|api-docs).*"); // allow swagger/openapi endpoints - the
																				// interface sometimes adds two slashes
	}
}
