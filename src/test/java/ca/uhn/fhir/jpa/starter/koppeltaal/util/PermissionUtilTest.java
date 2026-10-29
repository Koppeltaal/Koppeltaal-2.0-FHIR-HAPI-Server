package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.JwtSecurityInterceptor;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionUtilTest {


  @Test
  public void shouldGroupResourceOrigins() {
    Set<String> resourceOrigins = PermissionUtil.getResourceOrigins(Arrays.asList("system/*.c?resource-origin=Device/1,Device/2", "system/Task.c?resource-origin=Device/3"));

    assertTrue(resourceOrigins.contains("Device/1"));
    assertTrue(resourceOrigins.contains("Device/2"));
    assertTrue(resourceOrigins.contains("Device/3"));
  }

  @Test
  public void shouldReturnAnEmptyList() {
    Set<String> resourceOrigins = PermissionUtil.getResourceOrigins(Arrays.asList("system/*.cruds", "system/Task.c"));
    assertTrue(resourceOrigins.isEmpty());
  }

  @Test
  public void shouldHavePermission() {
    assertTrue(PermissionUtil.hasPermission(CrudOperation.DELETE, ResourceType.Device, "Device/123", "system/Task.cruds?resource-origin=Device/4 system/*.d?resource-origin=Device/34,Device/123"));
    assertTrue(PermissionUtil.hasPermission(CrudOperation.READ, ResourceType.Task, "Device/123", "system/Task.r"));
  }

  @Test
  public void shouldNotHavePermission() {
    assertFalse(PermissionUtil.hasPermission(CrudOperation.DELETE, ResourceType.Device, "Device/123", "system/Task.cruds?resource-origin=Device/4 system/*.d?resource-origin=Device/34,Device/12"));
  }

  @Test
  public void shouldOnlyHavePermissionForExactResourceOrigin() {
    assertFalse(PermissionUtil.hasPermission(CrudOperation.READ, ResourceType.Task, "Device/12", "system/Task.r?resource-origin=Device/123"));
    assertFalse(PermissionUtil.hasPermission(CrudOperation.READ, ResourceType.Task, "Device/123", "system/Task.r?resource-origin=Device/1234"));
    assertFalse(PermissionUtil.hasPermission(CrudOperation.READ, ResourceType.Task, "123", "system/Task.r?resource-origin=Device/123"));
    assertFalse(PermissionUtil.hasPermission(CrudOperation.READ, ResourceType.Task, null, "system/Task.r?resource-origin=Device/123"));
  }

  @Test
  public void shouldGetScopesForResourceTypeAndCrudOperation() {
    String scope = "system/Task.cruds?resource-origin=Device/1 system/*.r system/Patient.c system/Patient.ru?resource-origin=Device/2";

    assertEquals(List.of("system/Task.cruds?resource-origin=Device/1", "system/*.r"), PermissionUtil.getScopes(scope, "Task", CrudOperation.READ));
    assertEquals(List.of("system/*.r", "system/Patient.ru?resource-origin=Device/2"), PermissionUtil.getScopes(scope, "Patient", CrudOperation.READ));
    assertEquals(List.of("system/Patient.c"), PermissionUtil.getScopes(scope, "Patient", CrudOperation.CREATE));
    assertTrue(PermissionUtil.getScopes(scope, "Device", CrudOperation.DELETE).isEmpty());
    assertTrue(PermissionUtil.getScopes(null, "Device", CrudOperation.READ).isEmpty());
    assertTrue(PermissionUtil.getScopes(scope, null, CrudOperation.READ).isEmpty());
  }

  @Test
  public void shouldIgnoreScopesWithAnotherQuery() {
    // a scope with an unknown restriction must not count as ALL
    String scope = "system/Task.r?foo=bar system/Patient.r?resource-origin=Device/1&foo=bar system/Organization.r?resource-origin=Device/1";

    assertTrue(PermissionUtil.getScopes(scope, "Task", CrudOperation.READ).isEmpty());
    assertTrue(PermissionUtil.getScopes(scope, "Patient", CrudOperation.READ).isEmpty());
    assertEquals(List.of("system/Organization.r?resource-origin=Device/1"), PermissionUtil.getScopes(scope, "Organization", CrudOperation.READ));
  }

  @Test
  public void shouldPermitResourceOriginOnlyForExactDeviceReference() {
    List<String> granted = List.of("system/Task.r?resource-origin=Device/34,Device/456");

    assertTrue(PermissionUtil.permitsResourceOrigin(granted, "Device/34"));
    assertTrue(PermissionUtil.permitsResourceOrigin(granted, "Device/456"));
    assertFalse(PermissionUtil.permitsResourceOrigin(granted, "Device/4567"));
    assertFalse(PermissionUtil.permitsResourceOrigin(granted, "Device/45"));
    assertFalse(PermissionUtil.permitsResourceOrigin(granted, "Device/3"));
    assertFalse(PermissionUtil.permitsResourceOrigin(granted, null), "Only ALL gives access to resources without resource-origin");
    assertFalse(PermissionUtil.permitsResourceOrigin(List.of(), "Device/34"));
  }

  @Test
  public void shouldPermitEveryResourceOriginForAllScope() {
    List<String> all = List.of("system/Task.r?resource-origin=Device/1", "system/Task.r");

    assertTrue(PermissionUtil.permitsResourceOrigin(all, "Device/999"));
    assertTrue(PermissionUtil.permitsResourceOrigin(all, null));
  }

  @Test
  public void shouldFindFullScopeOnlyWhenAValidatedTokenIsPresent() {
    ServletRequestDetails withoutToken = new ServletRequestDetails();
    withoutToken.setServletRequest(new MockHttpServletRequest());
    assertTrue(PermissionUtil.findFullScope(withoutToken).isEmpty());

    String token = JWT.create().withClaim("scope", "system/Task.r").sign(Algorithm.HMAC256("super-secret"));

    // a token in the header alone is not enough, the JwtSecurityInterceptor marks the validated token
    MockHttpServletRequest notValidated = new MockHttpServletRequest();
    notValidated.addHeader("Authorization", "Bearer " + token);
    ServletRequestDetails withNotValidatedToken = new ServletRequestDetails();
    withNotValidatedToken.setServletRequest(notValidated);
    assertTrue(PermissionUtil.findFullScope(withNotValidatedToken).isEmpty());

    MockHttpServletRequest validated = new MockHttpServletRequest();
    validated.addHeader("Authorization", "Bearer " + token);
    validated.setAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE, token);
    ServletRequestDetails withValidatedToken = new ServletRequestDetails();
    withValidatedToken.setServletRequest(validated);
    assertEquals(Optional.of("system/Task.r"), PermissionUtil.findFullScope(withValidatedToken));
  }

  @Test
  public void shouldRecognizeConformanceResourceTypes() {
    assertTrue(PermissionUtil.isConformanceResourceType("ValueSet"));
    assertTrue(PermissionUtil.isConformanceResourceType("StructureDefinition"));
    assertFalse(PermissionUtil.isConformanceResourceType("Patient"));
    assertFalse(PermissionUtil.isConformanceResourceType(null));
  }
}
