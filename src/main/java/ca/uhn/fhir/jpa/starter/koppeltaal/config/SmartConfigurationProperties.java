package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("smart.configuration")
@Configuration
@EnableConfigurationProperties
public class SmartConfigurationProperties {

  private String issuer;
  private String jwks_uri;
  private String authorization_endpoint;
  private List<String> grant_types_supported = new ArrayList<>();
  private String token_endpoint;
  private List<String> token_endpoint_auth_methods_supported = new ArrayList<>();
  private List<String> scopes_supported = new ArrayList<>();
  private List<String> response_types_supported = new ArrayList<>();
  private String introspection_endpoint;
  private List<String> capabilities = new ArrayList<>();
  private List<String> code_challenge_methods_supported = new ArrayList<>();

  public String getIssuer() {
    return issuer;
  }

  public void setIssuer(String issuer) {
    this.issuer = issuer;
  }

  public String getJwks_uri() {
    return jwks_uri;
  }

  public void setJwks_uri(String jwks_uri) {
    this.jwks_uri = jwks_uri;
  }

  public String getAuthorization_endpoint() {
    return authorization_endpoint;
  }

  public void setAuthorization_endpoint(String authorization_endpoint) {
    this.authorization_endpoint = authorization_endpoint;
  }

  public List<String> getGrant_types_supported() {
    return grant_types_supported;
  }

  public void setGrant_types_supported(List<String> grant_types_supported) {
    this.grant_types_supported = grant_types_supported;
  }

  public String getToken_endpoint() {
    return token_endpoint;
  }

  public void setToken_endpoint(String token_endpoint) {
    this.token_endpoint = token_endpoint;
  }

  public List<String> getToken_endpoint_auth_methods_supported() {
    return token_endpoint_auth_methods_supported;
  }

  public void setToken_endpoint_auth_methods_supported(List<String> token_endpoint_auth_methods_supported) {
    this.token_endpoint_auth_methods_supported = token_endpoint_auth_methods_supported;
  }

  public List<String> getScopes_supported() {
    return scopes_supported;
  }

  public void setScopes_supported(List<String> scopes_supported) {
    this.scopes_supported = scopes_supported;
  }

  public List<String> getResponse_types_supported() {
    return response_types_supported;
  }

  public void setResponse_types_supported(List<String> response_types_supported) {
    this.response_types_supported = response_types_supported;
  }

  public String getIntrospection_endpoint() {
    return introspection_endpoint;
  }

  public void setIntrospection_endpoint(String introspection_endpoint) {
    this.introspection_endpoint = introspection_endpoint;
  }

  public List<String> getCapabilities() {
    return capabilities;
  }

  public void setCapabilities(List<String> capabilities) {
    this.capabilities = capabilities;
  }

  public List<String> getCode_challenge_methods_supported() {
    return code_challenge_methods_supported;
  }

  public void setCode_challenge_methods_supported(List<String> code_challenge_methods_supported) {
    this.code_challenge_methods_supported = code_challenge_methods_supported;
  }
}
