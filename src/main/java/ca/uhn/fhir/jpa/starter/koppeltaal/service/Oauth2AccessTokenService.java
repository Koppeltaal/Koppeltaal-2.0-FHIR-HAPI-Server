/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerSecurityConfiguration;
import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URISyntaxException;

/**
 *
 */
@Service
public class Oauth2AccessTokenService {

	private static final Log LOG = LogFactory.getLog(Oauth2AccessTokenService.class);

	private final JwtValidationService jwtValidationService;
	private final FhirServerSecurityConfiguration fhirServerSecurityConfiguration;

	public Oauth2AccessTokenService(JwtValidationService jwtValidationService, FhirServerSecurityConfiguration fhirServerSecurityConfiguration) {
		this.jwtValidationService = jwtValidationService;
		this.fhirServerSecurityConfiguration = fhirServerSecurityConfiguration;
	}

	public boolean validateToken(String token) {
		try {
			jwtValidationService.validate(token,
        fhirServerSecurityConfiguration.getAudience(),
        fhirServerSecurityConfiguration.getIssuer(),
        fhirServerSecurityConfiguration.getJwksEndpoint(),
        fhirServerSecurityConfiguration.getTokenValidationLeeway());
			return true;
		} catch (JWTVerificationException | IOException | JwkException | URISyntaxException e) {
			LOG.info("validateToken failed with message:" + e.getMessage(), e);
			return false;
		}
	}


}
