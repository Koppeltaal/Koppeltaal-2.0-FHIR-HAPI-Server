package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;

import org.hl7.fhir.instance.model.api.IBaseConformance;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * This interceptor customizes the generated {@link CapabilityStatement}. This is used to apply specific Koppeltaal-rules
 * like the `_revinclude` search parameter being forbidden.
 */
@Component
@Interceptor
public class CapabilityStatementInterceptor {
  public static final List<String> SUPPORTED_PATCH_MIME_TYPES = Arrays.asList(
    "*/*",
    "application/fhir+json",
    "application/fhir+xml",
    "application/json-patch+json",
    "application/xml-patch+xml"
  );

  public static final List<String> SUPPORTED_MIME_TYPES = Arrays.asList(
    "*/*",
    "xml",
    "json",
    "application/fhir+xml",
    "application/xml",
    "application/fhir+json",
    "application/json",
    "html/json",
    "html/xml"
  );

  public static final List<String> UNSUPPORTED_MIME_TYPES = Arrays.asList(
    "application/x-turtle",
    "application/fhir+turtle",
    "ttl",
    "html/turtle"
  );

  private static final Logger LOG = LoggerFactory.getLogger(CapabilityStatementInterceptor.class);

  @Hook(Pointcut.SERVER_CAPABILITY_STATEMENT_GENERATED)
  public void customize(IBaseConformance theCapabilityStatement) {

    LOG.info("Applying Koppeltaal-specific changes to the CapabilityStatement");

    CapabilityStatement capabilityStatement = (CapabilityStatement) theCapabilityStatement;

    List<CodeType> mimeCodeTypes = SUPPORTED_MIME_TYPES.stream()
      .map(CodeType::new)
      .collect(Collectors.toList());

    capabilityStatement.setFormat(mimeCodeTypes);

    capabilityStatement.setContained(Collections.emptyList());

    List<CapabilityStatement.CapabilityStatementRestComponent> restComponents = capabilityStatement.getRest();
    for (CapabilityStatement.CapabilityStatementRestComponent restComponent : restComponents) {
      List<CapabilityStatement.CapabilityStatementRestResourceComponent> resources = restComponent.getResource();
      for (CapabilityStatement.CapabilityStatementRestResourceComponent resource : resources) {
        resource.setSearchInclude(Collections.emptyList());
        resource.setSearchRevInclude(Collections.emptyList());
      }
    }

    capabilityStatement.addImplementationGuide("http://koppeltaal.nl/fhir/ImplementationGuide");

    LOG.debug("Applied Koppeltaal-specific changes to the CapabilityStatement");
  }
}
