package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import java.util.List;

public class AuthorizationDto {
  private String clientId;
  private String deviceId;
  private List<PermissionDto> permissions;

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getDeviceId() {
		return deviceId;
	}

	public void setDeviceId(String deviceId) {
		this.deviceId = deviceId;
	}

	public List<PermissionDto> getPermissions() {
		return permissions;
	}

	public void setPermissions(
		List<PermissionDto> permissions) {
		this.permissions = permissions;
	}
}
