package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.instance.model.api.IBaseReference;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Class that overrides {@link DaoResourceLinkResolver} only to exclude Bundles from the referential integrity check.
 * When a user searched, we map the {@link org.hl7.fhir.r4.model.AuditEvent} to the {@link Bundle}, causing an error.
 * HAPI only allows FHIRPaths to be excluded for deletion, not creation.
 */
@Component
public class DaoResourceLinkResolverOverride extends DaoResourceLinkResolver {

  private static final Logger LOG = LoggerFactory.getLogger(DaoResourceLinkResolverOverride.class);

  @Override
  public IResourceLookup findTargetResource(@NotNull RequestPartitionId theRequestPartitionId, RuntimeSearchParam theSearchParam, String theSourcePath, IIdType theSourceResourceId, String theResourceType, Class<? extends IBaseResource> theType, IBaseReference theReference, RequestDetails theRequest, TransactionDetails theTransactionDetails) {

    if(theType.isAssignableFrom(Bundle.class) || theType.isAssignableFrom(CapabilityStatement.class)) {
      LOG.info("Skipping findTargetResource() as [{}] is excluded - custom KT2 addition!", theType.getSimpleName());
      return null;
    }

    return super.findTargetResource(theRequestPartitionId, theSearchParam, theSourcePath, theSourceResourceId, theResourceType, theType, theReference, theRequest, theTransactionDetails);
  }
}
