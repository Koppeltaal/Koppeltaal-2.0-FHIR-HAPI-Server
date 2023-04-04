package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

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
}
