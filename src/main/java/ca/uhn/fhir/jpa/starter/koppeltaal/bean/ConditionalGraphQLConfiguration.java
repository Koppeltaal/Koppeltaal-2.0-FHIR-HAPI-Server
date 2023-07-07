package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.jpa.api.IDaoRegistry;
import ca.uhn.fhir.jpa.config.JpaConfig;
import ca.uhn.fhir.jpa.graphql.GraphQLProvider;
import ca.uhn.fhir.rest.server.util.ISearchParamRegistry;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * The following Bean is always initialized, even if spring boot disabled graphql. This Bean comes from the parent
 * hapi-fhir project and cannot easily be conditionally loaded. Therefore, we conditionally overwrite it in this project
 *
 * {@link ca.uhn.fhir.jpa.config.r4.JpaR4Config#graphQLProvider(ca.uhn.fhir.context.FhirContext, org.hl7.fhir.utilities.graphql.IGraphQLStorageServices, ca.uhn.fhir.context.support.IValidationSupport, ca.uhn.fhir.rest.server.util.ISearchParamRegistry, ca.uhn.fhir.jpa.api.IDaoRegistry)}
 */
@Configuration
public class ConditionalGraphQLConfiguration {

  @Bean(name = JpaConfig.GRAPHQL_PROVIDER_NAME)
  @Lazy
  @ConditionalOnProperty(value = "hapi.fhir.graphql_enabled", matchIfMissing = true, havingValue = "false")
  public GraphQLProvider graphQLProvider(FhirContext theFhirContext, IGraphQLStorageServices theGraphqlStorageServices, IValidationSupport theValidationSupport, ISearchParamRegistry theSearchParamRegistry, IDaoRegistry theDaoRegistry) {
    return null;
  }
}
