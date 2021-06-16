/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerSecurityConfiguration;
import java.util.Collections;
import java.util.List;
import javax.validation.constraints.Null;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.UriType;

/**
 * Interceptor that adds the
 */
public class Oauth2UrisStatementInterceptorForR4 {

	final FhirServerSecurityConfiguration fhirServerSecurityConfiguration;

	public Oauth2UrisStatementInterceptorForR4(FhirServerSecurityConfiguration fhirServerSecurityConfiguration) {
		this.fhirServerSecurityConfiguration = fhirServerSecurityConfiguration;
	}

	@Hook(Pointcut.SERVER_OUTGOING_RESPONSE)
	public void hookOutgoingResponse(IBaseResource resource, Pointcut pointcut) {


		if (resource instanceof CapabilityStatement) {
			CapabilityStatement capabilityStatement = (CapabilityStatement) resource;

			List<CapabilityStatement.CapabilityStatementRestComponent> rests = capabilityStatement.getRest();
			if (rests.isEmpty()) {
				capabilityStatement.addRest(); // Object exposes internal list on the getRest method. Nice.
			}
			for (CapabilityStatement.CapabilityStatementRestComponent rest : rests) {
				CapabilityStatement.CapabilityStatementRestSecurityComponent security;
				if (rest.hasSecurity()) {
					security = rest.getSecurity();
				} else {
					security = new CapabilityStatement.CapabilityStatementRestSecurityComponent();
					rest.setSecurity(security);
				}
				CodeableConcept concept = new CodeableConcept();
				concept.setCoding(Collections.singletonList(new Coding("http://hl7.org/fhir/restful-security-service", "SMART-on-FHIR", null)));
				concept.setText("OAuth2 using SMART-on-FHIR profile (see http://docs.smarthealthit.org)");
				security.setService(Collections.singletonList(concept));

				List<Extension> extensions = security.getExtension();
				Extension extension = getExtensionByUrl(extensions, "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris");
				if (extension == null) {
					extension = new Extension("http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris", null);
					extensions.add(extension);
				}

				setSecurityUrlExtension(extension, "token", fhirServerSecurityConfiguration.getTokenEndpoint());
				setSecurityUrlExtension(extension, "authorize", fhirServerSecurityConfiguration.getAuthorizationEndpoint());

			}

		}
	}

	@Null
	private Extension getExtensionByUrl(List<Extension> extensions, String url) {
		for (Extension extension : extensions) {
			if (StringUtils.equals(extension.getUrl(), url)) {
				return extension;
			}
		}
		return null;
	}

	private void setSecurityUrlExtension(Extension parent, String name, String url) {
		List<Extension> extensions = parent.getExtension();
		@Null Extension extension = getExtensionByUrl(extensions, name);
		UriType value = new UriType(url);
		if (extension == null) {
			parent.addExtension(name, value);
		} else {
			extension.setValue(value);
		}
	}

}
