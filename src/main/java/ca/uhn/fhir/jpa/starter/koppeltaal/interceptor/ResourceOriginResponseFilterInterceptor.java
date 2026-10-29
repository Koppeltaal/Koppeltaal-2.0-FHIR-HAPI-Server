package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes the entries from search and history Bundles that the requester is not allowed to read based on the
 * resource-origin. The {@link ResourceOriginSearchNarrowingInterceptor} only narrows searches, type level history and
 * the pages of an earlier search do not apply search parameters. This filter is the safety net for those.
 *
 * <p>Hooks into {@link Pointcut#SERVER_OUTGOING_RESPONSE} instead of {@link Pointcut#STORAGE_PRESHOW_RESOURCES}, as
 * other interceptors perform internal lookups (e.g. the Device of the requester) with the {@link RequestDetails} of
 * the client. Those lookups must not be filtered, only the response to the client.</p>
 */
@Interceptor
public class ResourceOriginResponseFilterInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(ResourceOriginResponseFilterInterceptor.class);

  /**
   * Interactions that return a result set Bundle. Selected on the interaction instead of the Bundle type, as the
   * client chooses the Bundle type of a page with the <code>_bundletype</code> parameter. Instance history is
   * filtered per version as well: versions written before the resource-origin was immutable for PATCH can have
   * another resource-origin than the current version.
   */
  private static final Set<RestOperationTypeEnum> RESULT_SET_OPERATIONS = EnumSet.of(
    RestOperationTypeEnum.SEARCH_TYPE,
    RestOperationTypeEnum.HISTORY_TYPE,
    RestOperationTypeEnum.HISTORY_INSTANCE,
    RestOperationTypeEnum.GET_PAGE
  );

  private final DaoRegistry daoRegistry;
  private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

  public ResourceOriginResponseFilterInterceptor(DaoRegistry daoRegistry, SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
    this.daoRegistry = daoRegistry;
    this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
  }

  @Hook(value = Pointcut.SERVER_OUTGOING_RESPONSE, order = -10)
  public void filterResponse(RequestDetails requestDetails, ResponseDetails responseDetails) {

    if (!RESULT_SET_OPERATIONS.contains(requestDetails.getRestOperationType())) return;
    if (!(responseDetails.getResponseResource() instanceof Bundle bundle)) return;

    final String scope = PermissionUtil.findFullScope(requestDetails).orElse(null);
    final boolean domainAdmin = ResourceOriginUtil.getRequesterClientId(requestDetails)
      .map((clientId) -> StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), clientId))
      .orElse(false);
    final Map<String, List<String>> readScopesPerResourceType = new HashMap<>();

    final int originalSize = bundle.getEntry().size();
    bundle.getEntry().removeIf((entry) -> !isPermitted(entry, requestDetails, scope, domainAdmin, readScopesPerResourceType));

    if (bundle.getEntry().size() != originalSize) {
      LOG.info("Removed {} of {} entries from the {} response of [{}], resource-origin not permitted",
        originalSize - bundle.getEntry().size(), originalSize, bundle.getType(), requestDetails.getRequestPath());
    }
  }

  private boolean isPermitted(Bundle.BundleEntryComponent entry, RequestDetails requestDetails, String scope,
      boolean domainAdmin, Map<String, List<String>> readScopesPerResourceType) {

    final Resource resource = entry.getResource();

    // Access granted on another basis than the resource-origin scopes (e.g. the delete flow) must be applied here as
    // well, otherwise those resources are removed from the response.

    if (resource == null) {
      // the history entry of a deletion has no resource, the version before the deletion holds the resource-origin
      final IdType deletedVersionId = new IdType(entry.getRequest().getUrl());
      final String resourceType = deletedVersionId.getResourceType();

      if (domainAdmin && "Device".equals(resourceType)) return true;

      final VersionBeforeDeletion versionBeforeDeletion = getVersionBeforeDeletion(deletedVersionId, requestDetails);

      if (versionBeforeDeletion.readable() && versionBeforeDeletion.resourceOrigin() == null
          && PermissionUtil.isConformanceResourceType(resourceType)) {
        return true; // managed by the domain, readable for every client
      }

      return PermissionUtil.permitsResourceOrigin(getReadScopes(scope, resourceType, readScopesPerResourceType),
        versionBeforeDeletion.resourceOrigin());
    }

    // the outcome of the search itself, OperationOutcome is also a storable resource type
    if (resource instanceof OperationOutcome && entry.getSearch().getMode() == Bundle.SearchEntryMode.OUTCOME) return true;

    // the domain admin can access Device resources, see ResourceOriginAuthorizationInterceptor
    if (domainAdmin && resource instanceof Device) return true;

    final String resourceOrigin;
    try {
      resourceOrigin = ResourceOriginUtil.getResourceOriginDeviceId(resource)
        .map(IIdType::getValue)
        .orElse(null);
    } catch (RuntimeException e) {
      // e.g. two resource-origin extensions, removing the resource keeps the rest of the response available
      LOG.warn("Removed [{}] from the response, its resource-origin can not be read: {}",
        resource.getIdElement().getValue(), e.getMessage());
      return false;
    }

    if (resourceOrigin == null && PermissionUtil.isConformanceResourceType(resource.fhirType())) {
      return true; // managed by the domain, readable for every client
    }

    return PermissionUtil.permitsResourceOrigin(getReadScopes(scope, resource.fhirType(), readScopesPerResourceType), resourceOrigin);
  }

  /**
   * The resource-origin of the version before a deletion. When that version can not be read, the resource-origin is
   * unknown and only an ALL permission gives access to the deletion.
   */
  private record VersionBeforeDeletion(boolean readable, String resourceOrigin) {
    static final VersionBeforeDeletion UNREADABLE = new VersionBeforeDeletion(false, null);
  }

  private VersionBeforeDeletion getVersionBeforeDeletion(IdType deletedVersionId, RequestDetails requestDetails) {
    if (!deletedVersionId.hasResourceType() || !deletedVersionId.hasIdPart() || !deletedVersionId.isVersionIdPartValidLong()) {
      return VersionBeforeDeletion.UNREADABLE;
    }

    final long deletedVersion = deletedVersionId.getVersionIdPartAsLong();
    if (deletedVersion <= 1) return VersionBeforeDeletion.UNREADABLE;

    try {
      final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(deletedVersionId.getResourceType());
      final IBaseResource previousVersion = resourceDao.read(deletedVersionId.withVersion(String.valueOf(deletedVersion - 1)), requestDetails, true);

      if (previousVersion == null) return VersionBeforeDeletion.UNREADABLE;

      return new VersionBeforeDeletion(true, ResourceOriginUtil.getResourceOriginDeviceId(previousVersion)
        .map(IIdType::getValue)
        .orElse(null));
    } catch (RuntimeException e) {
      LOG.debug("Could not determine the resource-origin before the deletion of {}: {}", deletedVersionId.getValue(), e.getMessage());
      return VersionBeforeDeletion.UNREADABLE;
    }
  }

  private List<String> getReadScopes(String scope, String resourceType, Map<String, List<String>> readScopesPerResourceType) {
    return readScopesPerResourceType.computeIfAbsent(resourceType,
      (type) -> PermissionUtil.getScopes(scope, type, CrudOperation.READ));
  }
}
