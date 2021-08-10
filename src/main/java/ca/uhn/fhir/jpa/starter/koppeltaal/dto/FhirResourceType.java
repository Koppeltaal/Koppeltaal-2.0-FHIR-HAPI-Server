/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

public enum FhirResourceType {
  ACTIVITY_DEFINITION,
  CARE_TEAM,
  TASK,
  PATIENT,
  PRACTITIONER,
  RELATED_PERSON,
  ENDPOINT,
  ORGANIZATION,
  SUBSCRIPTION;
}
