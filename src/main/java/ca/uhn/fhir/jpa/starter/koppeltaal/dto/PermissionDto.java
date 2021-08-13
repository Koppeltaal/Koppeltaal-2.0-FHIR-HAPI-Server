/*
 * Copyright (c) Stichting Koppeltaal 2021.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hl7.fhir.r4.model.ResourceType;

/**
 *
 */
public class PermissionDto {
	private UUID id;
	private Set<String> grantedDeviceIds = new HashSet<>();
	private ResourceType resourceType;
	private CrudOperation operation;
	private PermissionScope scope;

	public UUID getId() {
		return id;
	}

	public void setId(UUID id) {
		this.id = id;
	}

	public Set<String> getGrantedDeviceIds() {
		return grantedDeviceIds;
	}

	public void setGrantedDeviceIds(Set<String> grantedDeviceIds) {
		this.grantedDeviceIds = grantedDeviceIds;
	}

	public ResourceType getResourceType() {
		return resourceType;
	}

	public void setResourceType(ResourceType resourceType) {
		this.resourceType = resourceType;
	}

	public CrudOperation getOperation() {
		return operation;
	}

	public void setOperation(CrudOperation operation) {
		this.operation = operation;
	}

	public PermissionScope getScope() {
		return scope;
	}

	public void setScope(PermissionScope scope) {
		this.scope = scope;
	}

	@Override
	public String toString() {
		return "PermissionDto{" +
			"id=" + id +
			", grantedDeviceIds=" + grantedDeviceIds +
			", resourceType=" + resourceType +
			", operation=" + operation +
			", scope=" + scope +
			'}';
	}

}
