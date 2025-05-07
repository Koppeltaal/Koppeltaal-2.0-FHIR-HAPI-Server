package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.jpa.searchparam.extractor.PathAndRef;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.storage.IResourcePersistentId;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Class that overrides {@link DaoResourceLinkResolver} only to exclude Bundles from the referential integrity check.
 * When a user searched, we map the {@link org.hl7.fhir.r4.model.AuditEvent} to the {@link Bundle}, causing an error.
 * HAPI only allows FHIRPaths to be excluded for deletion, not creation.
 */
@Component
public class DaoResourceLinkResolverOverride<T extends IResourcePersistentId<?>> extends DaoResourceLinkResolver<T> {

  private static final Logger LOG = LoggerFactory.getLogger(DaoResourceLinkResolverOverride.class);

  @Override
  public IResourceLookup findTargetResource(
    @Nonnull RequestPartitionId theRequestPartitionId,
    String theSourceResourceName,
    PathAndRef thePathAndRef,
    RequestDetails theRequest,
    TransactionDetails theTransactionDetails) {

    // Skip referential integrity check in `AuditEvent.entity.what` for the Bundle and the CapabilityStatement
    String sourcePath = thePathAndRef.getPath();
    IIdType referenceElement = thePathAndRef.getRef().getReferenceElement();
    if (StringUtils.equals("AuditEvent.entity.what", sourcePath)) {
      String type = "";
      if (referenceElement.getResourceType() != null) {
        type = thePathAndRef.getRef().getReferenceElement().getResourceType(); //required by the profile
      } else if (thePathAndRef.getRef().getResource() != null) {
        type = thePathAndRef.getRef().getResource().fhirType();
      }
      if (StringUtils.equals("CapabilityStatement", type) || StringUtils.equals("Bundle", type)) {
        LOG.info("Skipping findTargetResource() as [{}] is excluded - custom KT2 addition!", type);
        return null;
      }
    }

    if (StringUtils.equals("Bundle", theSourceResourceName) || StringUtils.equals("CapabilityStatement", theSourceResourceName) || StringUtils.equals("ConceptMap", theSourceResourceName)) {
      LOG.info("Skipping findTargetResource() as [{}] is excluded - custom KT2 addition!", theSourceResourceName);
      return null;
    }

    return super.findTargetResource(theRequestPartitionId, theSourceResourceName, thePathAndRef, theRequest, theTransactionDetails);
  }
}
