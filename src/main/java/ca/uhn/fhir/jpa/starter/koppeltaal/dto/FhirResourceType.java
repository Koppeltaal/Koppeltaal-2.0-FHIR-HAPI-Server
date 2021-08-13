/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.ResourceType;

public enum FhirResourceType {
  ACTIVITY_DEFINITION(ResourceType.ActivityDefinition.name()),
  CARE_TEAM(ResourceType.CareTeam.name()),
  TASK(ResourceType.Task.name()),
  PATIENT(ResourceType.Patient.name()),
  PRACTITIONER(ResourceType.Practitioner.name()),
  RELATED_PERSON(ResourceType.RelatedPerson.name()),
  ENDPOINT(ResourceType.Endpoint.name()),
  ORGANIZATION(ResourceType.Organization.name()),
  DEVICE(ResourceType.Device.name()),
  SUBSCRIPTION(ResourceType.Subscription.name());

  private String resourceName;

  FhirResourceType(String resourceName) {
	  this.resourceName = resourceName;
  }

	public static FhirResourceType fromResourceName(String resourceName) {
		for(FhirResourceType resourceType : values()) {
			if(StringUtils.equals(resourceType.getResourceName(), resourceName)) {
				return resourceType;
			}
		}

		return null;
	}

	public String getResourceName() {
		return resourceName;
	}
}
