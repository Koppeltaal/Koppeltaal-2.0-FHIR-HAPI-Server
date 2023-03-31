package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.service.SmartBackendServiceAuthorizationService;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationInterceptor;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Not using the {@link AuthorizationInterceptor} as custom {@link org.hl7.fhir.CompartmentDefinition} objects are not allowed.
 */
@Interceptor
public class ResourceOriginAuthorizationInterceptor extends BaseAuthorizationInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(ResourceOriginAuthorizationInterceptor.class);
  private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;

  public ResourceOriginAuthorizationInterceptor(DaoRegistry daoRegistry,
                                                IFhirResourceDao<Device> deviceDao,
                                                SmartBackendServiceAuthorizationService smartBackendServiceAuthorizationService,
                                                SmartBackendServiceConfiguration smartBackendServiceConfiguration) {

    super(daoRegistry, deviceDao, smartBackendServiceAuthorizationService);
    this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
  }

  @Hook(value = Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED, order = -10)
  public void authorizeRequest(RequestDetails requestDetails, HttpServletRequest request) {

    final String resourceName = requestDetails.getResourceName();

    if (StringUtils.isBlank(resourceName)) return; //capabilities service

    if ("Device".equals(resourceName)) {
      // the domain admin should be able to create devices no matter what.
      // devices are used for authorizations, but this makes it impossible
      // to create the initial device needed for the domain admin without whitelisting
      final String requesterClientId = ResourceOriginUtil.getRequesterClientId(requestDetails)
        .orElseThrow(() -> new AuthenticationException("client_id not present"));

      if (StringUtils.equals(smartBackendServiceConfiguration.getDomainAdminClientId(), requesterClientId)) return;
    }

    validate(requestDetails, request);
  }

  private void validate(RequestDetails requestDetails, HttpServletRequest request) {
    final String resourceName = requestDetails.getResourceName();

    String authorization = request.getHeader("Authorization");
    String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(authorization, "Bearer"));
    DecodedJWT decode = JWT.decode(token); //no need to validate, handled by the JwtSecurityInterceptor
    Claim scopeClaim = decode.getClaim("scope");

    RequestTypeEnum requestType = requestDetails.getRequestType();

    String[] scopes = scopeClaim.asString().split(" ");

    List<String> relevantPermissions = Arrays.stream(scopes)
      .filter((scope) -> scope.startsWith("system/*.") || scope.startsWith("system/" + resourceName + "."))
      .collect(Collectors.toList());

    if (requestType == RequestTypeEnum.POST) {
      // no need to validate resource-origin
      boolean hasPermission = relevantPermissions.stream()
        .anyMatch((scope) ->
            scope.startsWith("system/*.c") ||
            scope.startsWith("system/" + resourceName + ".c")
        );

      if (!hasPermission) {
        throw new ForbiddenOperationException("Unauthorized");
      }
      return;
    }

    // non-create request, always involves existing entities
    String crudsRegex = getCrudsRegex(requestType);

    boolean hasPermission;

    if(requestDetails.getRequestType() == RequestTypeEnum.GET && requestDetails.getResource() == null) { //read all
       hasPermission = relevantPermissions.stream()
        .map((permission) -> StringUtils.substringAfter(permission, "."))
        .anyMatch((permission) -> permission.matches(crudsRegex + ".*"));
    } else {
      String existingEntityResourceOrigin = getEntityResourceOrigin(requestDetails);

      hasPermission = relevantPermissions.stream()
        .map((permission) -> StringUtils.substringAfter(permission, "."))
        .anyMatch((permission) -> permission.matches(crudsRegex + "(?:\\?resource-origin=.*" + existingEntityResourceOrigin + "(,.*|$))?"));
    }

    if (!hasPermission) {
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

    return null; //no entity id - read all - will be managed by search narrowing
  }

  private String getCrudsRegex(RequestTypeEnum requestTypeEnum) {
    switch (requestTypeEnum) {
      case POST:
        return "cr?u?d?s?";
      case GET:
        return "c?ru?d?s?";
      case PATCH: //fallthrough to update
      case PUT:
        return "c?r?ud?s?";
      case DELETE:
        return "c?r?u?ds?";
      default:
        throw new ForbiddenOperationException("RequestType " + requestTypeEnum + " not supported");
    }
  }

  String getResourceOriginDeviceReference(IBaseResource requestDetailsResource, RequestDetails requestDetails) {
    Optional<IIdType> resourceOriginOptional = ResourceOriginUtil.getResourceOriginDeviceId(requestDetailsResource);

    if (resourceOriginOptional.isEmpty()) {
      LOG.warn("Found resource ({}) without resource-origin", requestDetailsResource.getIdElement());
      throw new ForbiddenOperationException("Unauthorized");
    }

    return resourceOriginOptional.get().getValue();
  }
}
