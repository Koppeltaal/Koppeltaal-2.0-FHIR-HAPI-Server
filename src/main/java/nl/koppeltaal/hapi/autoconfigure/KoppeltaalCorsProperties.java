package nl.koppeltaal.hapi.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** koppeltaal.cors: the starter's hapi.fhir.cors settings, for Koppeltaal's own CorsInterceptor. */
@ConfigurationProperties("koppeltaal.cors")
public class KoppeltaalCorsProperties {

  private boolean enabled;
  private boolean allowCredentials = true;
  private List<String> allowedOrigin = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public boolean isAllowCredentials() {
    return allowCredentials;
  }

  public void setAllowCredentials(boolean allowCredentials) {
    this.allowCredentials = allowCredentials;
  }

  public List<String> getAllowedOrigin() {
    return allowedOrigin;
  }

  public void setAllowedOrigin(List<String> allowedOrigin) {
    this.allowedOrigin = allowedOrigin;
  }
}
