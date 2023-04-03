package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PermissionUtil {

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

    String[] scopes = scopeClaim.asString().split(" ");

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
}
