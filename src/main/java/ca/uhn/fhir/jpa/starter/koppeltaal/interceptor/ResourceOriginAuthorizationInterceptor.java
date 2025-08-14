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
      LOG.debug("Skipping authorization - blank resource name (capabilities service)");
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
      
      LOG.debug("Device resource - checking if domain admin. ClientId: {}, DomainAdminClientId: {}", 
          requesterClientId, smartBackendServiceConfiguration.getDomainAdminClientId());

      if (StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), requesterClientId)) {
        LOG.debug("Skipping authorization - domain admin accessing Device resource");
        return;
      }
    }

    LOG.debug("Proceeding to validate() for resource: {}", resourceName);
    validate(requestDetails);
  }

  private void validate(RequestDetails requestDetails) {

    List<String> relevantPermissions = PermissionUtil.getScopesForRequest(requestDetails);
    RequestTypeEnum requestType = requestDetails.getRequestType();

    if (requestType == RequestTypeEnum.POST) {

      if (relevantPermissions.isEmpty()) {
        LOG.warn("No permission found, user tried to [{}] a [{}], but the found scopes are [{}]",
            requestType, requestDetails.getResourceName(), PermissionUtil.getFullScope(requestDetails));
        throw new ForbiddenOperationException("Unauthorized");
      }
      return;
    }

    // non-create request, always involves existing entities
    String crudsRegex = PermissionUtil.getCrudsRegex(requestType);

    boolean hasPermission;

    if (requestDetails.getRequestType() == RequestTypeEnum.GET && requestDetails.getResource() == null) { // read all
      hasPermission = relevantPermissions.stream()
          .map((permission) -> StringUtils.substringAfter(permission, "."))
          .anyMatch((permission) -> permission.matches(crudsRegex + ".*"));
    } else {
      String existingEntityResourceOrigin = getEntityResourceOrigin(requestDetails);
      
      LOG.debug("=== AUTHORIZATION CHECK ===");
      LOG.debug("Request Type: {}, Resource: {}/{}", requestType, requestDetails.getResourceName(), requestDetails.getId());
      LOG.debug("Existing entity resource-origin: {}", existingEntityResourceOrigin);
      LOG.debug("CRUDS regex: {}", crudsRegex);
      LOG.debug("Relevant permissions from scope: {}", relevantPermissions);
      LOG.debug("Full scope: {}", PermissionUtil.getFullScope(requestDetails));
      LOG.debug("Request parameters: {}", requestDetails.getParameters());

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
    }
  }

  private String getEntityResourceOrigin(RequestDetails requestDetails) {
    final IIdType resourceId = requestDetails.getId();
    if (resourceId != null) {
      final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(requestDetails.getResourceName());
      IBaseResource existingResource = resourceDao.read(resourceId, requestDetails);
      return getResourceOriginDeviceReference(existingResource, requestDetails);
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

      throw new ForbiddenOperationException("Unauthorized");
    }

    return resourceOriginOptional.get().getValue();
  }
}
