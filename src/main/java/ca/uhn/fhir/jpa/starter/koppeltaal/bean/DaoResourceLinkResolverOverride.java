package ca.uhn.fhir.jpa.starter.koppeltaal.bean;

import ca.uhn.fhir.interceptor.model.RequestPartitionId;
import ca.uhn.fhir.jpa.dao.index.DaoResourceLinkResolver;
import ca.uhn.fhir.jpa.model.cross.IResourceLookup;
import ca.uhn.fhir.jpa.searchparam.extractor.PathAndRef;
import ca.uhn.fhir.i18n.Msg;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.api.server.storage.IResourcePersistentId;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import jakarta.annotation.Nonnull;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that overrides {@link DaoResourceLinkResolver} to relax the referential integrity check on
 * {@code AuditEvent.entity.what}. HAPI only allows FHIRPaths to be excluded for deletion, not creation.
 * <p>
 * Two things live on that path that a resource lookup cannot satisfy. A search maps the
 * {@link org.hl7.fhir.r4.model.AuditEvent} to the {@link Bundle} it returned, which is never stored. And after a
 * GDPR erase the patient is physically gone, while OPG-013 still wants the destroy event to name it.
 * </p>
 * <p>
 * An audit record states what was touched. That something is no longer there - or never was - is a fact to record,
 * not a reason to refuse the record, so an unresolvable target costs the link row and nothing more.
 * </p>
 * <p>
 * One entity reference is indexed along more than one path: the plain {@code AuditEvent.entity.what} and a
 * type-specific {@code AuditEvent.entity.what.where(resolve() is Patient)}. The extractor answers {@code resolve()}
 * from a stub built out of the reference string rather than from the database, so the type-specific path goes on
 * matching after the target is erased and has to be exempted as well.
 * </p>
 *
 * <p>Registered in place of HAPI's bean by the
 * {@link ca.uhn.fhir.jpa.starter.koppeltaal.config.DaoResourceLinkResolverOverrideRegistrar}.</p>
 */
public class DaoResourceLinkResolverOverride<T extends IResourcePersistentId<?>> extends DaoResourceLinkResolver<T> {

  private static final Logger LOG = LoggerFactory.getLogger(DaoResourceLinkResolverOverride.class);

  private static final int TARGET_DOES_NOT_EXIST = 1094;
  private static final int TARGET_WAS_DELETED = 1096;

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
    if (isAuditedEntityPath(sourcePath)) {
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

    if (StringUtils.equals("Bundle", theSourceResourceName) || StringUtils.equals("CapabilityStatement", theSourceResourceName) || StringUtils.equals("ConceptMap", theSourceResourceName) || StringUtils.equals("ImplementationGuide", theSourceResourceName)) {
      LOG.info("Skipping findTargetResource() as [{}] is excluded - custom KT2 addition!", theSourceResourceName);
      return null;
    }

    if (isAuditedEntityPath(sourcePath)) {
      return findAuditedResource(theRequestPartitionId, theSourceResourceName, thePathAndRef, theRequest, theTransactionDetails);
    }

    return findTargetResourceInHapi(theRequestPartitionId, theSourceResourceName, thePathAndRef, theRequest, theTransactionDetails);
  }

  /** Every path the entity reference is indexed along, type-specific variants included. */
  private static boolean isAuditedEntityPath(String sourcePath) {
    return StringUtils.startsWith(sourcePath, "AuditEvent.entity.what");
  }

  /**
   * The audited resource, or {@code null} when it is not there to be found. With referential integrity on write
   * enabled HAPI reports that as {@link InvalidRequestException} HAPI-1094 for a target that does not exist and
   * HAPI-1096 for one that was deleted; here both mean the event is written without an indexed link. Anything else
   * is a different complaint and still stands.
   */
  private IResourceLookup findAuditedResource(
    RequestPartitionId theRequestPartitionId,
    String theSourceResourceName,
    PathAndRef thePathAndRef,
    RequestDetails theRequest,
    TransactionDetails theTransactionDetails) {

    try {
      return findTargetResourceInHapi(theRequestPartitionId, theSourceResourceName, thePathAndRef, theRequest, theTransactionDetails);
    } catch (InvalidRequestException e) {
      if (!isMissingTarget(e)) {
        throw e;
      }
      LOG.info("Recording an AuditEvent for [{}], which does not resolve - custom KT2 addition!",
        thePathAndRef.getRef().getReferenceElement().getValue());
      return null;
    }
  }

  /** What HAPI itself makes of the reference. Separated so a test can stand in for it. */
  IResourceLookup findTargetResourceInHapi(
    RequestPartitionId theRequestPartitionId,
    String theSourceResourceName,
    PathAndRef thePathAndRef,
    RequestDetails theRequest,
    TransactionDetails theTransactionDetails) {

    return super.findTargetResource(theRequestPartitionId, theSourceResourceName, thePathAndRef, theRequest, theTransactionDetails);
  }

  private static boolean isMissingTarget(InvalidRequestException e) {
    String message = StringUtils.defaultString(e.getMessage());
    return message.startsWith(Msg.code(TARGET_DOES_NOT_EXIST)) || message.startsWith(Msg.code(TARGET_WAS_DELETED));
  }
}
