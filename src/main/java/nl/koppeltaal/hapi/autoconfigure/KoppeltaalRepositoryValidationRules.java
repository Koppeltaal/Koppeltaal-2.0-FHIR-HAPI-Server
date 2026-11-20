package nl.koppeltaal.hapi.autoconfigure;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.interceptor.validation.IRepositoryValidatingRule;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingInterceptor;
import ca.uhn.fhir.jpa.interceptor.validation.RepositoryValidatingRuleBuilder;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.TokenParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.StructureDefinition;

/** The starter customization RepositoryValidationInterceptorFactoryR4 of before the extension, ported verbatim: the stored-StructureDefinition rules (skipped when the capped search reports no size) plus the KT2 profile rules. */
public class KoppeltaalRepositoryValidationRules {

  private final FhirContext fhirContext;
  private final RepositoryValidatingRuleBuilder repositoryValidatingRuleBuilder;
  private final IFhirResourceDao structureDefinitionResourceProvider;

	public KoppeltaalRepositoryValidationRules(
			RepositoryValidatingRuleBuilder repositoryValidatingRuleBuilder, DaoRegistry daoRegistry) {
		this.repositoryValidatingRuleBuilder = repositoryValidatingRuleBuilder;
		this.fhirContext = daoRegistry.getSystemDao().getContext();
		structureDefinitionResourceProvider = daoRegistry.getResourceDao("StructureDefinition");
	}

  public RepositoryValidatingInterceptor buildUsingStoredStructureDefinitions() {

		IBundleProvider results = structureDefinitionResourceProvider.search(new SearchParameterMap()
				.setLoadSynchronous(true)
				.add(StructureDefinition.SP_KIND, new TokenParam("resource")));

    Integer resultCount = results.size();
    if(resultCount != null) {
      Map<String, List<StructureDefinition>> structureDefintions = results.getResources(0, resultCount).stream()
          .map(StructureDefinition.class::cast)
          .collect(Collectors.groupingBy(StructureDefinition::getType));

      structureDefintions.forEach((key, value) -> {
        String[] urls = value.stream().map(StructureDefinition::getUrl).toArray(String[]::new);
        repositoryValidatingRuleBuilder
            .forResourcesOfType(key)
            .requireAtLeastOneProfileOf(urls)
            .and()
            .requireValidationToDeclaredProfiles();
      });
    }

    this.build();

    List<IRepositoryValidatingRule> rules = repositoryValidatingRuleBuilder.build();
    return new RepositoryValidatingInterceptor(fhirContext, rules);
  }


  public RepositoryValidatingInterceptor build() {

		// Customize the ruleBuilder here to have the rules you want! We will give a simple example
		// of enabling validation for all Patient resources
    this.repositoryValidatingRuleBuilder
      .forResourcesOfType("ActivityDefinition")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2ActivityDefinition")
      .forResourcesOfType("AuditEvent")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2AuditEvent")
      .forResourcesOfType("CareTeam")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2CareTeam")
      .forResourcesOfType("Device")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Device")
      .forResourcesOfType("Endpoint")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Endpoint")
      .forResourcesOfType("Organization")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Organization")
      .forResourcesOfType("Patient")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Patient")
      .forResourcesOfType("Practitioner")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Practitioner")
      .forResourcesOfType("RelatedPerson")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2RelatedPerson")
      .forResourcesOfType("Subscription")
      .requireAtLeastProfile("http://koppeltaal.nl/fhir/StructureDefinition/KT2Subscription")
      .forResourcesOfType("Task")
      .requireAtLeastOneProfileOf(
          "http://koppeltaal.nl/fhir/StructureDefinition/KT2Task",
          "http://koppeltaal.nl/fhir/StructureDefinition/KT2DeletePendingTask")
      .and()
      .requireValidationToDeclaredProfiles();

		// Do not customize below this line
		List<IRepositoryValidatingRule> rules = repositoryValidatingRuleBuilder.build();
		return new RepositoryValidatingInterceptor(fhirContext, rules);
	}
}
