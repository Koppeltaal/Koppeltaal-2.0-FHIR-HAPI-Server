package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "smart.backend.service")
public class SmartBackendServiceConfiguration {

	private String baseUrl;
	private String authorizationToken;
	private String domainAdminClientId;

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getAuthorizationToken() {
		return authorizationToken;
	}

	public void setAuthorizationToken(String authorizationToken) {
		this.authorizationToken = authorizationToken;
	}

	public String getDomainAdminClientId() {
		return domainAdminClientId;
	}

	public void setDomainAdminClientId(String domainAdminClientId) {
		this.domainAdminClientId = domainAdminClientId;
	}
}
