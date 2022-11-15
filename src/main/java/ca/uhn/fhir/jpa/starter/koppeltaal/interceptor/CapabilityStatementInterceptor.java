package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * This interceptor customizes the generated {@link CapabilityStatement}. This is used to apply specific Koppeltaal-rules
 * like the `_revinclude` search parameter being forbidden.
 */
@Component
@Interceptor
public class CapabilityStatementInterceptor {
  private static final Logger LOG = LoggerFactory.getLogger(CapabilityStatementInterceptor.class);

  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public void customize(IBaseConformance theCapabilityStatement) {

    LOG.info("Applying Koppeltaal-specific changes to the CapabilityStatement");

    CapabilityStatement capabilityStatement = (CapabilityStatement) theCapabilityStatement;

    capabilityStatement.setContained(Collections.emptyList());

    List<CapabilityStatement.CapabilityStatementRestComponent> restComponents = capabilityStatement.getRest();
    for (CapabilityStatement.CapabilityStatementRestComponent restComponent : restComponents) {
      List<CapabilityStatement.CapabilityStatementRestResourceComponent> resources = restComponent.getResource();
      for (CapabilityStatement.CapabilityStatementRestResourceComponent resource : resources) {
        resource.setSearchInclude(Collections.emptyList());
        resource.setSearchRevInclude(Collections.emptyList());
      }
    }

    LOG.debug("Applied Koppeltaal-specific changes to the CapabilityStatement");
  }
}
