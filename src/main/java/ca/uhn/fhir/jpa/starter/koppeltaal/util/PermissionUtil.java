package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.JwtSecurityInterceptor;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.ResourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PermissionUtil {

  private static final Logger LOG = LoggerFactory.getLogger(PermissionUtil.class);

  private static final String RESOURCE_ORIGIN_PARAMETER = "?resource-origin=";

  /**
   * Conformance and terminology resources are managed by the Koppeltaal domain and do not contain care data.
   * When they have no resource-origin, every authenticated client may read them.
   */
  private static final Set<String> CONFORMANCE_RESOURCE_TYPES = Set.of(
    "CapabilityStatement",
    "CodeSystem",
    "ImplementationGuide",
    "NamingSystem",
    "SearchParameter",
    "StructureDefinition",
    "ValueSet"
  );

  //IMPORTANT: Keep in mind that this is a POC implementation, and we simply use an in-memory solution here.
  //Actual solutions should use quick storage mechanisms such as Redis to keep the start over multiple nodes and server reboots
  private static final Map<String, String> CLIENT_ID_TO_SCOPE_MAP = new HashMap<>();

  public static void createOrUpdateScope(String deviceId, String scope) {
    CLIENT_ID_TO_SCOPE_MAP.put(deviceId, scope);
  }

  public static Optional<String> getScope(String clientId) {
    return Optional.ofNullable(CLIENT_ID_TO_SCOPE_MAP.get(clientId));
  }

  /**
   * Stores the scope of the requester, used for the authorization of subscription notifications. Only the validated
   * access token is used, see {@link #findAccessToken(RequestDetails)}.
   */
  public static void refreshScopeCache(RequestDetails requestDetails) {
    findFullScope(requestDetails).ifPresent((scope) ->
      ResourceOriginUtil.getRequesterClientId(requestDetails).ifPresent((clientId) ->
        CLIENT_ID_TO_SCOPE_MAP.put(clientId, scope)
      )
    );
  }

  /**
   * Returns the scopes of the requester that grant the {@link CrudOperation} on the
   * {@link RequestDetails#getResourceName()}. Does not execute any logic with <code>resource-origin</code> parameters.
   */
  public static List<String> getScopesForRequest(RequestDetails requestDetails, CrudOperation crudOperation) {
    return getScopes(getFullScope(requestDetails), requestDetails.getResourceName(), crudOperation);
  }

  /**
   * Returns the scopes from the scope string that grant the {@link CrudOperation} on the given resource type. Only
   * scopes without a query (ALL) or with exactly a <code>?resource-origin=</code> query (OWN or GRANTED) are valid, a
   * scope with another query is ignored instead of counting as ALL.
   */
  public static List<String> getScopes(String scopeString, String resourceName, CrudOperation crudOperation) {
    if (StringUtils.isBlank(scopeString) || StringUtils.isBlank(resourceName)) return Collections.emptyList();

    final String scopeRegex = "^system\\/(?:\\*|" + Pattern.quote(resourceName) + ")\\." + getCrudsRegex(crudOperation)
      + "(?:" + Pattern.quote(RESOURCE_ORIGIN_PARAMETER) + "[^?&\\s]*)?$";

    return Arrays.stream(scopeString.split(" "))
      .filter((scope) -> scope.matches(scopeRegex))
      .collect(Collectors.toList());
  }

  /**
   * Checks whether one of the scopes allows access to a resource with the given resource-origin. A scope without
   * <code>?resource-origin=</code> (ALL) allows every resource-origin. Scopes with a resource-origin (OWN or GRANTED)
   * only allow resources of which the resource-origin is exactly one of the listed Device references.
   *
   * @param scopes         scopes that already match the resource type and crud operation
   * @param resourceOrigin the resource-origin (e.g. <code>Device/123</code>) of the resource, <code>null</code> when
   *                       the resource has no resource-origin. Only an ALL scope gives access to those.
   */
  public static boolean permitsResourceOrigin(List<String> scopes, String resourceOrigin) {
    return scopes.stream().anyMatch((scope) -> {
      if (!scope.contains(RESOURCE_ORIGIN_PARAMETER)) return true;
      if (StringUtils.isBlank(resourceOrigin)) return false;

      return Arrays.asList(StringUtils.substringAfter(scope, RESOURCE_ORIGIN_PARAMETER).split(","))
        .contains(resourceOrigin);
    });
  }

  public static boolean isConformanceResourceType(String resourceType) {
    return resourceType != null && CONFORMANCE_RESOURCE_TYPES.contains(resourceType);
  }

  /**
   * The access token of the request, the only place where it is read. Only a token that the
   * {@link JwtSecurityInterceptor} validated is returned, a token in the header alone is not trusted.
   */
  public static Optional<DecodedJWT> findAccessToken(RequestDetails requestDetails) {
    if (!(requestDetails instanceof ServletRequestDetails servletRequestDetails)
        || servletRequestDetails.getServletRequest() == null) {
      return Optional.empty();
    }

    final Object token = servletRequestDetails.getServletRequest()
      .getAttribute(JwtSecurityInterceptor.VALIDATED_ACCESS_TOKEN_ATTRIBUTE);

    return token instanceof String validatedToken && StringUtils.isNotBlank(validatedToken)
      ? Optional.of(JWT.decode(validatedToken))
      : Optional.empty();
  }

  /**
   * The scope of the access token, or an empty optional when the request has no access token.
   */
  public static Optional<String> findFullScope(RequestDetails requestDetails) {
    return findAccessToken(requestDetails).map((accessToken) -> accessToken.getClaim("scope").asString());
  }

  private static String getFullScope(RequestDetails requestDetails) {
    return findFullScope(requestDetails).orElseThrow(() -> {
      LOG.warn("Request without `Authorization` header found, can not determine access.");
      return new ForbiddenOperationException("Unauthorized");
    });
  }

  private static String getCrudsRegex(CrudOperation crudOperation) {
    switch (crudOperation) {
      case CREATE:
        return "cr?u?d?s?";
      case READ:
        return "c?ru?d?s?";
      case UPDATE:
        return "c?r?ud?s?";
      case DELETE:
        return "c?r?u?ds?";
      default:
        throw new ForbiddenOperationException("CrudOperation " + crudOperation + " not supported");
    }
  }

  public static Set<String> getResourceOrigins(List<String> scopes) {

    return scopes.stream()
      .map((scope) -> StringUtils.substringAfter(scope, RESOURCE_ORIGIN_PARAMETER))
      .filter(StringUtils::isNotBlank)
      .flatMap((resourceOrigins) -> Pattern.compile(",").splitAsStream(resourceOrigins))
      .collect(Collectors.toSet());
  }

  /**
   * Checks whether the scope on the access token has access to a particular state
   *
   * @param crudOperation type of crud action
   * @param resourceType Type of resource being checked
   * @param entityResourceOrigin The resource-origin Device reference (e.g. <code>Device/123</code>) of the resource being checked
   * @param scopeString The complete scope string from the access_token
   * @return whether the permission os found on the scope
   */
  public static boolean hasPermission(CrudOperation crudOperation, ResourceType resourceType, String entityResourceOrigin, String scopeString) {

    List<String> scopes = getScopes(scopeString, resourceType.name(), crudOperation);

    boolean hasPermission = permitsResourceOrigin(scopes, entityResourceOrigin);

    LOG.info("Checked permission:\n\n\tCrudOperation=[{}]\n\tResourceType=[{}]\n\tentityResourceOrigin=[{}]\n\tscopes=[{}]\n\t\thasPermission=[{}]",
      crudOperation, resourceType, entityResourceOrigin, scopes, hasPermission);

    return hasPermission;
  }
}
