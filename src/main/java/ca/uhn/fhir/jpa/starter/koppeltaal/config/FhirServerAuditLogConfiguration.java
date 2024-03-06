package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 *
 */
@Configuration
@ConfigurationProperties(prefix = "fhir.server.auditlog")
public class FhirServerAuditLogConfiguration {
	private boolean enabled;
  @Deprecated
	private String site;
	private Observer observer = new Observer();

	public Observer getObserver() {
		return observer;
	}

	public void setObserver(Observer observer) {
		this.observer = observer;
	}

	@Deprecated
  public String getSite() {
		return site;
	}

	public void setSite(String site) {
		this.site = site;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public static final class Observer {
		Identifier identifier = new Identifier();

		public Identifier getIdentifier() {
			return identifier;
		}

		public void setIdentifier(Identifier identifier) {
			this.identifier = identifier;
		}
	}

	public static class Identifier {
		String system;
		String value;

		public String getSystem() {
			return system;
		}

		public void setSystem(String system) {
			this.system = system;
		}

		public String getValue() {
			return value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}
}
