package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.SearchParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Not using the {@link AuthorizationInterceptor} as custom
 * {@link org.hl7.fhir.CompartmentDefinition} objects are not allowed.
 */
@Interceptor
public class ResourceOriginAuthorizationInterceptor extends BaseAuthorizationInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(ResourceOriginAuthorizationInterceptor.class);
  private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

  public ResourceOriginAuthorizationInterceptor(DaoRegistry daoRegistry,
      IFhirResourceDao<Device> deviceDao,
      SmartBackendServiceConfiguration smartBackendServiceConfiguration) {

    super(daoRegistry, deviceDao);
    this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
  }

  @Hook(value = Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED, order = -9)
  public void authorizeRequest(RequestDetails requestDetails) {

    LOG.debug("=== ResourceOriginAuthorizationInterceptor.authorizeRequest() CALLED ===");
    LOG.debug("Request: {} {}", requestDetails.getRequestType(), requestDetails.getCompleteUrl());
    LOG.debug("Resource Name: {}", requestDetails.getResourceName());
    LOG.debug("Resource ID: {}", requestDetails.getId());
    LOG.debug("Request Headers: Authorization={}", requestDetails.getHeader("Authorization") != null ? "Present" : "Missing");

    final String resourceName = requestDetails.getResourceName();

    if (StringUtils.isBlank(resourceName)) {
      LOG.debug("Skipping authorization - capabilities service");
      return; // capabilities service
    }

    if ("ImplementationGuide".equals(resourceName) && requestDetails.getRequestType() == RequestTypeEnum.GET) {
      LOG.debug("Skipping authorization - ImplementationGuide GET request");
      return; // part of the `CapabilityStatement.implementationGuides`
    }

    if ("Device".equals(resourceName)) {
      // the domain admin should be able to create devices no matter what.
      // devices are used for authorizations, but this makes it impossible
      // to create the initial device needed for the domain admin without whitelisting
      final String requesterClientId = ResourceOriginUtil.getRequesterClientId(requestDetails)
          .orElseThrow(() -> new AuthenticationException("client_id not present"));

      if (StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), requesterClientId)) {
        LOG.debug("Skipping authorization - domain admin accessing Device resource");
        return; // domain admin can access Device resources
      }
    }

    validate(requestDetails);
  }

  private void validate(RequestDetails requestDetails) {
    List<String> relevantPermissions = PermissionUtil.getScopesForRequest(requestDetails);
    RequestTypeEnum requestType = requestDetails.getRequestType();

    LOG.debug("Relevant permissions found: {}", relevantPermissions);
    LOG.debug("Full scope available: {}", PermissionUtil.getFullScope(requestDetails));

    if (requestType == RequestTypeEnum.POST) {

      if (relevantPermissions.isEmpty()) {
        LOG.warn("No permission found, user tried to [{}] a [{}], but the found scopes are [{}]",
            requestType, requestDetails.getResourceName(), PermissionUtil.getFullScope(requestDetails));
        throw new ForbiddenOperationException("Unauthorized");
      }
      LOG.debug("POST request authorized for {}", requestDetails.getResourceName());
      return;
    }

    // non-create request, always involves existing entities
    String crudsRegex = PermissionUtil.getCrudsRegex(requestType);

    boolean hasPermission;

    if (requestDetails.getRequestType() == RequestTypeEnum.GET && requestDetails.getId() == null) { // read all
      LOG.debug("GET request for multiple resources (read all) - checking permissions");
      hasPermission = relevantPermissions.stream()
          .map((permission) -> StringUtils.substringAfter(permission, "."))
          .anyMatch((permission) -> permission.matches(crudsRegex + ".*"));
      LOG.debug("Read-all permission check result: {}", hasPermission);
    } else {
      LOG.debug("Checking permission for specific resource operation");
      String existingEntityResourceOrigin = getEntityResourceOrigin(requestDetails);

      LOG.debug("Checking permission for {}/{} with resource-origin: {}",
          requestDetails.getResourceName(), requestDetails.getId(), existingEntityResourceOrigin);

//      system/Practitioner.crus?resource-origin=Device/b4decd94-15c0-43c1-8200-f8e5f04cf90b
      hasPermission = relevantPermissions.stream(
        )
          .map((permission) -> {
            String afterDot = StringUtils.substringAfter(permission, ".");
            String pattern = crudsRegex + "(?:\\?resource-origin=.*" + existingEntityResourceOrigin + "(,.*|$))?";
            boolean matches = afterDot.matches(pattern);
            LOG.debug("Checking permission '{}' against pattern '{}': {}", afterDot, pattern, matches);
            return matches;
          })
          .anyMatch(Boolean::booleanValue);

      LOG.debug("Final authorization result: {}", hasPermission);
    }

    if (!hasPermission) {
      LOG.warn("No permission found, user tried to [{}] a [{}], but the found scopes are [{}]",
          requestType, requestDetails.getResourceName(), PermissionUtil.getFullScope(requestDetails));
      throw new ForbiddenOperationException("Unauthorized");
    } else {
      LOG.debug("Request authorized successfully for {} on {}", requestType, requestDetails.getResourceName());
    }

    LOG.debug("{} request authorized for {}", requestType, requestDetails.getResourceName());
  }

  private String getEntityResourceOrigin(RequestDetails requestDetails) {
    final IIdType resourceId = requestDetails.getId();
    if (resourceId != null) {
      try {
        final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(requestDetails.getResourceName());

        // First try to read without allowDeleted flag
        IBaseResource existingResource;
        try {
          existingResource = resourceDao.read(resourceId, requestDetails);
        } catch (ResourceGoneException e) {
          // Resource is deleted - check if this is a history request
          if (isFullHistoryCheck(requestDetails)) {
            LOG.debug("Resource {}/{} is deleted, but this is a history request - reading with allowDeleted=true",
                     requestDetails.getResourceName(), resourceId.getIdPart());
            // For history requests, we need to read the deleted resource to check its resource-origin
            existingResource = resourceDao.read(resourceId, requestDetails, true);
          } else {
            // For non-history requests, deleted resources should return 410 Gone
            LOG.debug("Resource {}/{} is deleted and this is not a history request - returning 410 Gone",
                     requestDetails.getResourceName(), resourceId.getIdPart());
            throw e;
          }
        }

        return getResourceOriginDeviceReference(existingResource, requestDetails);
      } catch (ResourceGoneException e) {
        LOG.warn("Failed to read resource {}/{} for authorization check",
                 requestDetails.getResourceName(), resourceId.getIdPart());
        throw e;
      }
    }

    return null; // no entity id - read all - will be managed by search narrowing
  }


  String getResourceOriginDeviceReference(IBaseResource requestDetailsResource, RequestDetails requestDetails) {
    Optional<IIdType> resourceOriginOptional = ResourceOriginUtil.getResourceOriginDeviceId(requestDetailsResource);

    if (resourceOriginOptional.isEmpty()) {
      LOG.warn("Found resource ({}) without resource-origin", requestDetailsResource.getIdElement());

      if (requestDetailsResource instanceof SearchParameter || requestDetailsResource instanceof Device || requestDetailsResource instanceof CodeSystem) {
        // CodeSystems and SearchParameters are created via simplifier releases on server start and do
        // not contain a resource-origin.
        // It is important that we can update these if the user has permission.
        // Devices are currently created without resource-origin by domain admin, will
        // be resolved in the future.
        return "no-resource-origin-on-search-parameter";
      }

      // Check if this is a deleted resource for a full history request
      if (requestDetailsResource.isDeleted() && isFullHistoryCheck(requestDetails)) {
        LOG.debug("Found deleted resource for history request, attempting to get resource-origin from history");
        return getResourceOriginFromHistory(requestDetailsResource, requestDetails);
      }

      throw new ForbiddenOperationException("Unauthorized");
    }

    return resourceOriginOptional.get().getValue();
  }

  private boolean isFullHistoryCheck(RequestDetails requestDetails) {
    String requestPath = requestDetails.getRequestPath();
    return requestPath != null && requestPath.endsWith("/_history");
  }

  private String getResourceOriginFromHistory(IBaseResource deletedResource, RequestDetails requestDetails) {
    try {
      final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(requestDetails.getResourceName());
      IIdType resourceId = deletedResource.getIdElement();

      LOG.debug("Attempting to find resource-origin from history for resource {}", resourceId);

      if (resourceId.hasVersionIdPart()) {
        try {
          long currentVersion = Long.parseLong(resourceId.getVersionIdPart());
          if (currentVersion > 1) {
            IIdType previousVersionId = resourceId.withVersion(String.valueOf(currentVersion - 1));
            IBaseResource previousVersion = resourceDao.read(previousVersionId, requestDetails, true);

            Optional<IIdType> resourceOriginOptional = ResourceOriginUtil.getResourceOriginDeviceId(previousVersion);
            if (resourceOriginOptional.isPresent()) {
              LOG.debug("Found resource-origin from previous version: {}", resourceOriginOptional.get().getValue());
              return resourceOriginOptional.get().getValue();
            }
          }
        } catch (Exception e) {
          LOG.debug("Could not read previous version: {}", e.getMessage());
        }
      }

      LOG.warn("Could not determine resource-origin from history for deleted resource {}", resourceId);
      throw new ForbiddenOperationException("Cannot authorize history access for deleted resource without resource-origin");

    } catch (Exception e) {
      LOG.error("Error while trying to get resource-origin from history for resource {}: {}",
                deletedResource.getIdElement(), e.getMessage());
      throw new ForbiddenOperationException("Cannot authorize history access for deleted resource");
    }
  }
}
