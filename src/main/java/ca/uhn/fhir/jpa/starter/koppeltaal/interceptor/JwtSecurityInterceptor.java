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
import javax.servlet.http.HttpServletRequest;
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
	private final Oauth2AccessTokenService oauth2AccessTokenService;

	public JwtSecurityInterceptor(Oauth2AccessTokenService oauth2AccessTokenService) {
		this.oauth2AccessTokenService = oauth2AccessTokenService;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_PROCESSED)
	public void incomingRequestPreProcessed(HttpServletRequest request) {

		final String requestURI = request.getRequestURI();
		if (isFhirApiRequest(requestURI)) {
			try {
				String authorization = request.getHeader("Authorization");
				String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(authorization, "Bearer"));
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
		}
	}

	private boolean isFhirApiRequest(String servletPath) {
		return StringUtils.startsWith(servletPath, "/fhir") && !StringUtils.startsWith(servletPath, "/fhir/metadata");
	}
}
