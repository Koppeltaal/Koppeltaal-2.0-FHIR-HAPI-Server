package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.IInterceptorBroadcaster;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.model.DeleteConflict;
import ca.uhn.fhir.jpa.api.model.DeleteConflictList;
import ca.uhn.fhir.jpa.delete.DeleteConflictOutcome;
import ca.uhn.fhir.jpa.delete.ThreadSafeResourceDeleterSvc;
import ca.uhn.fhir.jpa.interceptor.CascadingDeleteInterceptor;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.DeleteCascadeModeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.api.server.storage.TransactionDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A cascading delete (<code>_cascade=delete</code>) that authorizes every resource it deletes. HAPI's
 * {@link CascadingDeleteInterceptor} deletes the resources that reference the deleted resource without the
 * resource-origin checks of the {@link ResourceOriginAuthorizationInterceptor}. Here the requester needs a delete
 * permission for the resource type and resource-origin of every referencing resource.
 *
 * <p>The referencing resources are checked when HAPI reports them as delete conflicts, and again right before each
 * of them is deleted (also when the deletion is retried). Like HAPI's own cascading delete this is not atomic: HAPI
 * finds the conflicts in rounds and deletes each referencing resource in its own transaction. When a later round
 * contains a resource the requester may not delete, the request fails with a 403, while the resources deleted in the
 * earlier rounds, which the requester was allowed to delete, stay deleted.</p>
 */
@Interceptor
public class ResourceOriginCascadingDeleteInterceptor extends CascadingDeleteInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(ResourceOriginCascadingDeleteInterceptor.class);

  private final DaoRegistry daoRegistry;
  private final boolean securityEnabled;

  public ResourceOriginCascadingDeleteInterceptor(FhirContext theFhirContext,
      DaoRegistry theDaoRegistry,
      IInterceptorBroadcaster theInterceptorBroadcaster,
      ThreadSafeResourceDeleterSvc theThreadSafeResourceDeleterSvc,
      boolean securityEnabled) {

    super(theFhirContext, theDaoRegistry, theInterceptorBroadcaster, theThreadSafeResourceDeleterSvc);
    this.daoRegistry = theDaoRegistry;
    this.securityEnabled = securityEnabled;
  }

  @Override
  @Hook(value = Pointcut.STORAGE_PRESTORAGE_DELETE_CONFLICTS, order = CASCADING_DELETE_INTERCEPTOR_ORDER)
  public DeleteConflictOutcome handleDeleteConflicts(DeleteConflictList theConflictList, RequestDetails theRequest,
      TransactionDetails theTransactionDetails) {

    if (securityEnabled && shouldCascade(theRequest) == DeleteCascadeModeEnum.DELETE) {
      final String scope = PermissionUtil.findFullScope(theRequest).orElse(null);

      for (DeleteConflict conflict : theConflictList) {
        if (!mayDelete(readResource(conflict.getSourceId()), scope)) {
          deny(conflict.getSourceId(), theRequest);
        }
      }
    }

    return super.handleDeleteConflicts(theConflictList, theRequest, theTransactionDetails);
  }

  /**
   * Called by HAPI right before each cascaded delete, with the resource as it is deleted.
   */
  @Hook(Pointcut.STORAGE_CASCADE_DELETE)
  public void authorizeCascadedDelete(RequestDetails theRequest, DeleteConflictList theConflictList, IBaseResource theResource) {
    if (securityEnabled && !mayDelete(theResource, PermissionUtil.findFullScope(theRequest).orElse(null))) {
      deny(theResource.getIdElement(), theRequest);
    }
  }

  /**
   * A requester without a (validated) scope may not cascade, also not the domain admin who may delete Devices.
   */
  private boolean mayDelete(IBaseResource resource, String scope) {
    if (resource == null) {
      return false;
    }

    final List<String> deleteScopes = PermissionUtil.getScopes(scope, resource.fhirType(), CrudOperation.DELETE);
    if (deleteScopes.isEmpty()) {
      return false;
    }

    try {
      final String resourceOrigin = ResourceOriginUtil.getResourceOriginDeviceId(resource)
        .map(IIdType::getValue)
        .orElse(null);

      return PermissionUtil.permitsResourceOrigin(deleteScopes, resourceOrigin);
    } catch (RuntimeException e) {
      LOG.warn("Could not determine the resource-origin of [{}]: {}", resource.getIdElement().getValue(), e.getMessage());
      return false;
    }
  }

  private IBaseResource readResource(IIdType resourceId) {
    try {
      return daoRegistry.getResourceDao(resourceId.getResourceType())
        .read(resourceId.toVersionless(), new SystemRequestDetails());
    } catch (RuntimeException e) {
      LOG.warn("Could not read [{}] for the cascading delete authorization: {}", resourceId.getValue(), e.getMessage());
      return null;
    }
  }

  private void deny(IIdType resourceId, RequestDetails theRequest) {
    LOG.warn("Denied cascading delete of [{}] for [{}], the requester may not delete it",
      resourceId.getValue(), theRequest != null ? theRequest.getRequestPath() : null);
    throw new ForbiddenOperationException("Unauthorized");
  }
}
