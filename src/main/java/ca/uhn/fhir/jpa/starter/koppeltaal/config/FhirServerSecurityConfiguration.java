/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 *
 */
@Configuration
@ConfigurationProperties(prefix = "fhir.server.security")
public class FhirServerSecurityConfiguration {

	String introspectEndpoint;
	String authorizationEndpoint;
	String tokenEndpoint;
  String jwksEndpoint;
	String audience;
  String issuer;
	boolean enabled = true;
	long tokenValidationLeeway = 60;

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

	public String getAudience() {
		return audience;
	}

	public void setAudience(String audience) {
		this.audience = audience;
	}

	public String getAuthorizationEndpoint() {
		return authorizationEndpoint;
	}

	public void setAuthorizationEndpoint(String authorizationEndpoint) {
		this.authorizationEndpoint = authorizationEndpoint;
	}

	public String getIntrospectEndpoint() {
		return introspectEndpoint;
	}

	public void setIntrospectEndpoint(String introspectEndpoint) {
		this.introspectEndpoint = introspectEndpoint;
	}

  public String getJwksEndpoint() {
    return jwksEndpoint;
  }

  public void setJwksEndpoint(String jwksEndpoint) {
    this.jwksEndpoint = jwksEndpoint;
  }

	public String getTokenEndpoint() {
		return tokenEndpoint;
	}

	public void setTokenEndpoint(String tokenEndpoint) {
		this.tokenEndpoint = tokenEndpoint;
	}

	public long getTokenValidationLeeway() {
		return tokenValidationLeeway;
	}

	public void setTokenValidationLeeway(long tokenValidationLeeway) {
		this.tokenValidationLeeway = tokenValidationLeeway;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}
}
