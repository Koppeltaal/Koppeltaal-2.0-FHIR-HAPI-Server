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
import java.util.Optional;
import java.util.stream.Collectors;

public class AccessTokenUtil {
  public static DecodedJWT getDecodedAccessToken(RequestDetails requestDetails) {

    String authorization = requestDetails.getHeader("Authorization");
    String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(authorization, "Bearer"));

    if(StringUtils.isBlank(token)) {
      throw new ForbiddenOperationException("Unauthorized");
    }

    return JWT.decode(token);
  }

  public static List<String> getScopesForResourceType(RequestDetails requestDetails) {
    final String resourceName = requestDetails.getResourceName();

    DecodedJWT decodedAccessToken = getDecodedAccessToken(requestDetails);

    Claim scopeClaim = decodedAccessToken.getClaim("scope");

    String[] scopes = scopeClaim.asString().split(" ");

    return Arrays.stream(scopes)
      .filter((scope) -> scope.startsWith("system/*.") || scope.startsWith("system/" + resourceName + "."))
      .collect(Collectors.toList());
  }
}
