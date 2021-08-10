package ca.uhn.fhir.jpa.starter.koppeltaal.dto;

import java.util.List;

public class AuthorizationDto {
  private String clientId;
  private List<PermissionDto> permissions;

  public AuthorizationDto() {

  }

  public AuthorizationDto(String clientId, List<PermissionDto> permissions) {
    this.clientId = clientId;
    this.permissions = permissions;
  }

  public String getClientId() {
    return clientId;
  }

  public List<PermissionDto> getPermissions() {
    return permissions;
  }

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public void setPermissions(
		List<PermissionDto> permissions) {
		this.permissions = permissions;
	}
}
