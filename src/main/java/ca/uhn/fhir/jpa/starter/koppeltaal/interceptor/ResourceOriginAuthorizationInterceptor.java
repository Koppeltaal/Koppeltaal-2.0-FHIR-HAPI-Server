package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CodeSystem;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.SearchParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Not using the {@link AuthorizationInterceptor} as custom
 * {@link org.hl7.fhir.CompartmentDefinition} objects are not allowed.
 *
 * <p>Denies by default: only the interactions of TOP-KT-002a are allowed, see
 * {@link #isSupportedInteraction(RequestDetails)}. Everything else (system level history, search, transaction and
 * batch Bundles, PATCH, conditional create and the other operations) is denied, as these would bypass the
 * resource-origin checks below. Allowed operations: type level <code>$validate</code>, the terminology operations on
 * ValueSet and CodeSystem, and the maintenance operations of the domain admin.</p>
 *
 * <p><code>$expunge</code> and deleting multiple resources at once are disabled in the server configuration
 * (<code>expunge_enabled</code> and <code>allow_multiple_delete</code>). Cascading deletes are authorized per resource
 * by the {@link ResourceOriginCascadingDeleteInterceptor}.</p>
 *
 * <p>Where the matrix refuses, the kt2-delete-flow marker may still grant access to the delete flow; see
 * {@link DeleteFlowAccess}.</p>
 */
@Interceptor
public class ResourceOriginAuthorizationInterceptor extends BaseAuthorizationInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(ResourceOriginAuthorizationInterceptor.class);

  /**
   * The only operation that is offered to applications (TOP-KT-010), on type level only. On instance level, the
   * <code>mode=delete</code> outcome reveals the resources that reference the instance.
   */
  static final String OPERATION_VALIDATE = "$validate";

  /**
   * Read only operations on terminology. They resolve any ValueSet or CodeSystem by its canonical url, including the
   * resources an expansion depends on, so they require an ALL read permission on the type (or the domain admin).
   */
  static final Set<String> TERMINOLOGY_OPERATIONS = Set.of("$expand", "$lookup", "$validate-code");
  static final Set<String> TERMINOLOGY_RESOURCE_TYPES = Set.of("ValueSet", "CodeSystem");

  /**
   * Maintenance operations, only for the domain admin.
   */
  private static final Set<String> MAINTENANCE_OPERATIONS = Set.of("$reindex", "$trigger-subscription");

  /**
   * Not supported within Koppeltaal (TOP-KT-002b). Matched as prefix, as HAPI reads both a ":" and a "." as qualifier
   * separator. <code>_include</code> is supported: included resources of other resource-origins are removed by the
   * {@link ResourceOriginResponseFilterInterceptor}.
   */
  private static final List<String> UNSUPPORTED_SEARCH_PARAMETER_PREFIXES = List.of("_revinclude", "_contained");

  /** The resource types the kt2-delete-flow marker can grant access to, see {@link DeleteFlowAccess}. */
  private static final Set<String> DELETE_FLOW_TYPES = Set.of("Task", "AuditEvent");

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
    LOG.debug("Operation: {}", requestDetails.getRestOperationType());
    LOG.debug("Resource Name: {}", requestDetails.getResourceName());
    LOG.debug("Resource ID: {}", requestDetails.getId());
    LOG.debug("Request Headers: Authorization={}", requestDetails.getHeader("Authorization") != null ? "Present" : "Missing");

    final RestOperationTypeEnum operationType = requestDetails.getRestOperationType();
    final String resourceName = requestDetails.getResourceName();

    PermissionUtil.refreshScopeCache(requestDetails);

    if (operationType == RestOperationTypeEnum.METADATA) {
      LOG.debug("Skipping authorization - capabilities service");
      return;
    }

    if (operationType == RestOperationTypeEnum.GET_PAGE) {
      // the search itself was authorized and narrowed, the returned page is filtered by the
      // ResourceOriginResponseFilterInterceptor
      LOG.debug("Skipping authorization - page of an earlier search");
      return;
    }

    if (isMaintenanceOperation(requestDetails)) {
      if (!isDomainAdmin(requestDetails)) {
        deny(requestDetails);
      }
      LOG.info("Maintenance operation [{}] authorized for the domain admin", requestDetails.getOperation());
      return;
    }

    if (!isSupportedInteraction(requestDetails)) {
      LOG.warn("Denied unsupported interaction [{}] on [{}]", operationType, requestDetails.getRequestPath());
      throw new ForbiddenOperationException("Unauthorized");
    }

    if (operationType == RestOperationTypeEnum.SEARCH_TYPE) {
      ensureSupportedSearchParameters(requestDetails);
    }

    if (operationType == RestOperationTypeEnum.CREATE
        && StringUtils.isNotBlank(requestDetails.getHeader(Constants.HEADER_IF_NONE_EXIST))) {
      // not supported within Koppeltaal (TOP-KT-002a), a match returns the existing resource of any resource-origin
      throw new InvalidRequestException("Conditional create (If-None-Exist) is not supported");
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

  /**
   * Only type and instance level interactions are supported. System level interactions (capabilities and paging
   * excluded) have no resource name and are never supported within Koppeltaal.
   */
  static boolean isSupportedInteraction(RequestDetails requestDetails) {
    final RestOperationTypeEnum operationType = requestDetails.getRestOperationType();

    if (operationType == null || StringUtils.isBlank(requestDetails.getResourceName())) {
      return false;
    }

    switch (operationType) {
      case READ:
      case VREAD:
      case HISTORY_INSTANCE:
      case HISTORY_TYPE:
      case SEARCH_TYPE:
      case CREATE:
      case UPDATE:
      case DELETE:
        return true;
      case EXTENDED_OPERATION_TYPE:
        return OPERATION_VALIDATE.equals(requestDetails.getOperation()) || isTerminologyOperation(requestDetails);
      case EXTENDED_OPERATION_INSTANCE:
        return isTerminologyOperation(requestDetails);
      default:
        return false;
    }
  }

  private static boolean isTerminologyOperation(RequestDetails requestDetails) {
    return TERMINOLOGY_RESOURCE_TYPES.contains(requestDetails.getResourceName())
        && TERMINOLOGY_OPERATIONS.contains(requestDetails.getOperation());
  }

  private static boolean isMaintenanceOperation(RequestDetails requestDetails) {
    final RestOperationTypeEnum operationType = requestDetails.getRestOperationType();

    return (operationType == RestOperationTypeEnum.EXTENDED_OPERATION_SERVER
        || operationType == RestOperationTypeEnum.EXTENDED_OPERATION_TYPE
        || operationType == RestOperationTypeEnum.EXTENDED_OPERATION_INSTANCE)
        && MAINTENANCE_OPERATIONS.contains(requestDetails.getOperation());
  }

  private boolean isDomainAdmin(RequestDetails requestDetails) {
    return ResourceOriginUtil.getRequesterClientId(requestDetails)
        .map((clientId) -> StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), clientId))
        .orElse(false);
  }

  private void ensureSupportedSearchParameters(RequestDetails requestDetails) {
    final Map<String, String[]> parameters = requestDetails.getParameters();
    if (parameters == null) return;

    for (String parameterName : parameters.keySet()) {
      if (UNSUPPORTED_SEARCH_PARAMETER_PREFIXES.stream().anyMatch(parameterName::startsWith)) {
        throw new InvalidRequestException(String.format("Search parameter [%s] is not supported", parameterName));
      }
    }
  }

  private void validate(RequestDetails requestDetails) {
    final RestOperationTypeEnum operationType = requestDetails.getRestOperationType();

    LOG.debug("Full scope available: {}", PermissionUtil.findFullScope(requestDetails).orElse(null));

    // The kt2-delete-flow marker grants access on another basis than the resource-origin scopes: per interaction
    // here, and for the returned resources in the ResourceOriginResponseFilterInterceptor.
    switch (operationType) {
      case SEARCH_TYPE:
      case HISTORY_TYPE:
        // read all, the resource-origin is enforced by the search narrowing and the response filter
        if (PermissionUtil.isConformanceResourceType(requestDetails.getResourceName())) {
          LOG.debug("Read all of conformance resource {} authorized", requestDetails.getResourceName());
          return;
        }
        if (!hasPermission(requestDetails, CrudOperation.READ) && !narrowedToDeleteFlow(requestDetails)) {
          deny(requestDetails);
        }
        break;
      case CREATE:
        // the resource-origin of a new resource is always the requester, see InjectResourceOriginInterceptor
        requirePermission(requestDetails, CrudOperation.CREATE);
        break;
      case EXTENDED_OPERATION_TYPE:
        if (isTerminologyOperation(requestDetails)) {
          requireTerminologyAccess(requestDetails);
          break;
        }
        // $validate of the resource in the request body, does not involve stored resources
        if (!hasPermission(requestDetails, CrudOperation.CREATE) && !hasPermission(requestDetails, CrudOperation.UPDATE)) {
          deny(requestDetails);
        }
        break;
      case EXTENDED_OPERATION_INSTANCE: // terminology operation on a stored ValueSet or CodeSystem
        requireTerminologyAccess(requestDetails);
        break;
      case READ:
      case VREAD:
      case HISTORY_INSTANCE:
        requireInstancePermission(requestDetails, CrudOperation.READ);
        break;
      case UPDATE:
        requireInstancePermission(requestDetails, CrudOperation.UPDATE);
        break;
      case DELETE:
        requireInstancePermission(requestDetails, CrudOperation.DELETE);
        break;
      default:
        deny(requestDetails);
    }

    LOG.debug("{} request authorized for {}", operationType, requestDetails.getResourceName());
  }

  private boolean hasPermission(RequestDetails requestDetails, CrudOperation crudOperation) {
    return !PermissionUtil.getScopesForRequest(requestDetails, crudOperation).isEmpty();
  }

  private void requirePermission(RequestDetails requestDetails, CrudOperation crudOperation) {
    if (!hasPermission(requestDetails, crudOperation)) {
      deny(requestDetails);
    }
  }

  private void requireTerminologyAccess(RequestDetails requestDetails) {
    final boolean unrestrictedRead = PermissionUtil.permitsResourceOrigin(
        PermissionUtil.getScopesForRequest(requestDetails, CrudOperation.READ), null);

    if (!unrestrictedRead && !isDomainAdmin(requestDetails)) {
      deny(requestDetails);
    }
  }

  /**
   * Checks the permission against the resource-origin of the existing entity.
   */
  private void requireInstancePermission(RequestDetails requestDetails, CrudOperation crudOperation) {
    final List<String> scopes = PermissionUtil.getScopesForRequest(requestDetails, crudOperation);
    final boolean conformanceRead = crudOperation == CrudOperation.READ
        && PermissionUtil.isConformanceResourceType(requestDetails.getResourceName());
    // without a scope for the interaction, only the kt2-delete-flow marker can still grant it
    final boolean markerOnly = scopes.isEmpty() && !conformanceRead;

    if (markerOnly && !mayBeGrantedByTheMarker(requestDetails, crudOperation)) {
      deny(requestDetails);
    }

    // a conditional update or delete has no id, only an ALL permission allows these
    String resourceOrigin = null;

    if (requestDetails.getId() != null) {
      final IBaseResource existingResource = markerOnly
          ? readExistingResourceOrDeny(requestDetails)
          : readExistingResource(requestDetails);

      if (deleteFlowGrants(existingResource, crudOperation, requestDetails)) {
        LOG.debug("{} of {} authorized by the kt2-delete-flow marker", crudOperation, existingResource.getIdElement());
        return;
      }
      if (markerOnly) {
        deny(requestDetails);
      }

      final Optional<IIdType> resourceOriginOptional = readResourceOrigin(existingResource, requestDetails);

      if (resourceOriginOptional.isEmpty() && conformanceRead) {
        LOG.debug("Read of conformance resource {} without resource-origin authorized", existingResource.getIdElement());
        return; // managed by the domain, readable for every client
      }

      resourceOrigin = getResourceOriginDeviceReference(existingResource, requestDetails);
    }

    LOG.debug("Checking permission for {}/{} with resource-origin: {}",
        requestDetails.getResourceName(), requestDetails.getId(), resourceOrigin);

    if (!PermissionUtil.permitsResourceOrigin(scopes, resourceOrigin)) {
      deny(requestDetails);
    }
  }

  /**
   * The kt2-delete-flow marker only grants reading a Task or AuditEvent, or updating a Task, by its id and to an
   * active participant. Checked before the resource is read, so that nothing else learns whether it exists.
   */
  private boolean mayBeGrantedByTheMarker(RequestDetails requestDetails, CrudOperation crudOperation) {
    final String resourceName = requestDetails.getResourceName();
    final boolean grantable = crudOperation == CrudOperation.READ
        ? DELETE_FLOW_TYPES.contains(resourceName)
        : crudOperation == CrudOperation.UPDATE && "Task".equals(resourceName);
    return grantable && requestDetails.getId() != null
        && DeleteFlowAccess.isParticipant(ResourceOriginUtil.getDevice(requestDetails, deviceDao).orElse(null));
  }

  /** The kt2-delete-flow marker adds to the matrix; it never takes anything away, and it never grants a delete. */
  private boolean deleteFlowGrants(IBaseResource existingResource, CrudOperation crudOperation,
      RequestDetails requestDetails) {
    if (crudOperation == CrudOperation.DELETE || !DeleteFlowAccess.isDeleteFlowResource(existingResource)) {
      return false;
    }
    final Device caller = ResourceOriginUtil.getDevice(requestDetails, deviceDao).orElse(null);
    return crudOperation == CrudOperation.READ
        ? DeleteFlowAccess.mayRead(existingResource, caller)
        : DeleteFlowAccess.mayUpdate(existingResource, caller);
  }

  /** Where only the marker could grant access, what does not exist must look like what may not be read. */
  private IBaseResource readExistingResourceOrDeny(RequestDetails requestDetails) {
    try {
      return readExistingResource(requestDetails);
    } catch (ResourceNotFoundException | ResourceGoneException notReadable) {
      deny(requestDetails);
      return null; // not reached, deny throws
    }
  }

  /**
   * Without matrix read on Task or AuditEvent, the marker still lets a participant search the delete flow: its own
   * delete-pending Tasks and every delete-flow AuditEvent. Whatever else the search returns, included resources too,
   * still has to pass the {@link ResourceOriginResponseFilterInterceptor}.
   */
  private boolean narrowedToDeleteFlow(RequestDetails requestDetails) {
    final String resourceName = requestDetails.getResourceName();
    if (requestDetails.getRestOperationType() != RestOperationTypeEnum.SEARCH_TYPE
        || !DELETE_FLOW_TYPES.contains(resourceName)) {
      return false;
    }
    final Device caller = ResourceOriginUtil.getDevice(requestDetails, deviceDao).orElse(null);
    if (!DeleteFlowAccess.isParticipant(caller)) {
      return false;
    }
    final Map<String, String[]> narrowed = new HashMap<>(requestDetails.getParameters());
    addValue(narrowed, Constants.PARAM_SECURITY, DeleteFlowTaskUtil.DELETE_FLOW_SECURITY_TOKEN);
    if ("Task".equals(resourceName)) {
      addValue(narrowed, "owner", "Device/" + caller.getIdElement().getIdPart());
    }
    requestDetails.setParameters(narrowed);
    return true;
  }

  /** A repeated search parameter is an AND, so an application's own value narrows further, never wider. */
  private static void addValue(Map<String, String[]> parameters, String name, String value) {
    parameters.merge(name, new String[] {value}, ArrayUtils::addAll);
  }

  /**
   * A resource-origin that can not be read (e.g. two resource-origin extensions) denies access to the resource.
   */
  private Optional<IIdType> readResourceOrigin(IBaseResource resource, RequestDetails requestDetails) {
    try {
      return ResourceOriginUtil.getResourceOriginDeviceId(resource);
    } catch (RuntimeException e) {
      LOG.warn("Can not read the resource-origin of [{}]: {}", resource.getIdElement().getValue(), e.getMessage());
      deny(requestDetails);
      return Optional.empty();
    }
  }

  /**
   * A delete conflict (409) names the first resource that references the deleted resource, which can be a resource of
   * another resource-origin. The response only contains those details for a requester with an ALL delete permission
   * on every resource type. The AuditEventInterceptor (order 100) applies the same to the AuditEvent, which the
   * requester can read; the details are logged here.
   */
  @Hook(value = Pointcut.SERVER_PRE_PROCESS_OUTGOING_EXCEPTION, order = 200)
  public BaseServerResponseException hideDeleteConflictDetails(RequestDetails requestDetails, Throwable throwable) {
    final BaseServerResponseException withoutDetails = deleteConflictWithoutDetails(requestDetails, throwable);

    if (withoutDetails != null) {
      LOG.info("Delete conflict for [{}]: {}", requestDetails.getRequestPath(), throwable.getMessage());
    }

    return withoutDetails;
  }

  /**
   * @return the delete conflict without the referencing resource, or <code>null</code> when the exception is no delete
   * conflict or the requester may see the details.
   */
  static BaseServerResponseException deleteConflictWithoutDetails(RequestDetails requestDetails, Throwable throwable) {
    if (requestDetails == null
        || requestDetails.getRestOperationType() != RestOperationTypeEnum.DELETE
        || !(throwable instanceof ResourceVersionConflictException)) {
      return null;
    }

    final boolean unrestrictedDelete = PermissionUtil.findFullScope(requestDetails)
        .map((scope) -> PermissionUtil.permitsResourceOrigin(PermissionUtil.getScopes(scope, "*", CrudOperation.DELETE), null))
        .orElse(false);

    if (unrestrictedDelete) {
      return null;
    }

    final String deletedResource = requestDetails.getId() != null
        ? requestDetails.getId().toUnqualifiedVersionless().getValue()
        : requestDetails.getResourceName();

    return new ResourceVersionConflictException(String.format(
        "Unable to delete %s because at least one resource has a reference to this resource", deletedResource));
  }

  private void deny(RequestDetails requestDetails) {
    LOG.warn("No permission found, user tried to [{}] a [{}], but the found scopes are [{}]",
        requestDetails.getRestOperationType(), requestDetails.getResourceName(),
        PermissionUtil.findFullScope(requestDetails).orElse(null));
    throw new ForbiddenOperationException("Unauthorized");
  }

  private IBaseResource readExistingResource(RequestDetails requestDetails) {
    final IIdType resourceId = requestDetails.getId();
    try {
      final IFhirResourceDao<?> resourceDao = daoRegistry.getResourceDao(requestDetails.getResourceName());

      // Determine if we should allow reading deleted resources based on whether this is a history request
      boolean allowDeleted = isFullHistoryCheck(requestDetails);

      if (allowDeleted) {
        LOG.debug("History request for {}/{} - reading with allowDeleted=true",
                 requestDetails.getResourceName(), resourceId.getIdPart());
      }

      return resourceDao.read(resourceId, requestDetails, allowDeleted);
    } catch (ResourceGoneException e) {
      LOG.warn("Failed to read resource {}/{} for authorization check",
               requestDetails.getResourceName(), resourceId.getIdPart());
      throw e;
    }
  }

  /**
   * @return the resource-origin Device reference of the resource, or <code>null</code> for resources that are allowed
   * to exist without a resource-origin. Only an ALL permission gives access to the latter.
   */
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
        return null;
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
