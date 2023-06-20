package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.jpa.searchparam.extractor.PathAndRef;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;

/**
 * Class that overrides {@link DaoResourceLinkResolver} only to exclude Bundles from the referential integrity check.
 * When a user searched, we map the {@link org.hl7.fhir.r4.model.AuditEvent} to the {@link Bundle}, causing an error.
 * HAPI only allows FHIRPaths to be excluded for deletion, not creation.
 */
@Component
public class DaoResourceLinkResolverOverride extends DaoResourceLinkResolver {

  private static final Logger LOG = LoggerFactory.getLogger(DaoResourceLinkResolverOverride.class);

  @Override
  public IResourceLookup findTargetResource(@Nonnull RequestPartitionId theRequestPartitionId, String theSourceResourceName, PathAndRef thePathAndRef, RequestDetails theRequest, TransactionDetails theTransactionDetails) {

    if("Bundle".equals(theSourceResourceName) || "CapabilityStatement".equals(theSourceResourceName)) {
      LOG.info("Skipping findTargetResource() as [{}] is excluded - custom KT2 addition!", theSourceResourceName);
      return null;
    }

    return super.findTargetResource(theRequestPartitionId, theSourceResourceName, thePathAndRef, theRequest, theTransactionDetails);
  }
}
