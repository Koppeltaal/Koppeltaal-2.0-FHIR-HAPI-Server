package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

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
}
