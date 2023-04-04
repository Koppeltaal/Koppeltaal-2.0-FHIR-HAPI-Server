package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.r4.model.ResourceType;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PermissionUtil {

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
   * This helper method only returns scopes that relate to the {@link RequestDetails#getResourceName()} and the
   * {@link RequestDetails#getRequestType()}. Does not execute any logic with <code>resource-origin</code> parameters.
   *
   * @param requestDetails
   * @return
   */
  public static List<String> getScopesForRequest(RequestDetails requestDetails) {
    final String resourceName = requestDetails.getResourceName();

    DecodedJWT decodedAccessToken = getDecodedAccessToken(requestDetails);

    Claim scopeClaim = decodedAccessToken.getClaim("scope");
    String scopeString = scopeClaim.asString();

    //update the cache
    ResourceOriginUtil.getRequesterClientId(requestDetails).ifPresent((clientId) ->
      CLIENT_ID_TO_SCOPE_MAP.put(clientId, scopeString)
    );

    String[] scopes = scopeString.split(" ");

    String crudsRegex = getCrudsRegex(requestDetails.getRequestType());

    return Arrays.stream(scopes)
      .filter((scope) -> scope.matches("^system\\/(?:\\*|"+resourceName+")\\."+crudsRegex+".*"))
      .collect(Collectors.toList());
  }

  public static String getCrudsRegex(RequestTypeEnum requestTypeEnum) {
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
  public static String getCrudsRegex(CrudOperation crudOperation) {
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

  private static DecodedJWT getDecodedAccessToken(RequestDetails requestDetails) {

    String authorization = requestDetails.getHeader("Authorization");
    String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(authorization, "Bearer"));

    if(StringUtils.isBlank(token)) {
      throw new ForbiddenOperationException("Unauthorized");
    }

    return JWT.decode(token);
  }

  public static Set<String> getResourceOrigins(List<String> scopes) {

    return scopes.stream()
      .map((scope) -> StringUtils.substringAfter(scope, "?resource-origin="))
      .filter(StringUtils::isNotBlank)
      .flatMap((resourceOrigins) -> Pattern.compile(",").splitAsStream(resourceOrigins))
      .collect(Collectors.toSet());
  }

  /**
   * Checks whether the scope on the access token has access to a particular state
   *
   * @param crudOperation type of crud action
   * @param resourceType Type of resource being checked
   * @param entityResourceOrigin The resurce-origin of the resource being checked
   * @param scopeString The complete scope string from the access_token
   * @return whether the permission os found on the scope
   */
  public static boolean hasPermission(CrudOperation crudOperation, ResourceType resourceType, String entityResourceOrigin, String scopeString) {

    String[] scopes = scopeString.split(" ");

    String crudsRegex = getCrudsRegex(crudOperation);

    return Arrays.stream(scopes)
      .anyMatch((scope) -> scope.matches("^system\\/(?:\\*|"+resourceType.name()+")\\."+crudsRegex+"(?:\\?resource-origin=.*"+entityResourceOrigin+".*|$)"));
  }
}
