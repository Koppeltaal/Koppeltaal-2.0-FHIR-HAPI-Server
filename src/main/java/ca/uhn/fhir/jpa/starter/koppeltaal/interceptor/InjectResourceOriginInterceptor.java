package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static ca.uhn.fhir.rest.api.RestOperationTypeEnum.CREATE;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.StringType;

/**
 * <p>Interceptor that injects the https://koppeltaal.nl/resource-origin extension on
 * newly created {@link DomainResource} entities.</p>
 *
 * <p>This can be used to determine if applications have a granted permission on entities
 * they originally created.</p>
 */
@Interceptor
public class InjectResourceOriginInterceptor {

	private final static String EXTENSION_URL = "https://koppeltaal.nl/resource-origin";

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED)
	public void incomingRequestPreHandled(RequestDetails requestDetails, RestOperationTypeEnum operation) {

		final IBaseResource resource = requestDetails.getResource();
		final boolean isDomainResource = resource instanceof DomainResource;

		if(operation != CREATE || !isDomainResource) return;

		DomainResource domainResource = (DomainResource) resource;

		String authorization = requestDetails.getHeader("Authorization");
		String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(authorization, "Bearer"));

		// Already validated by the JwtSecurityInterceptor
		DecodedJWT decode = JWT.decode(token);

		final Claim azpClaim = decode.getClaim("azp");
		String resourceOrigin = azpClaim.asString();

		//TODO: We might want to turn this into a Reference(Organization) and look it up based on the client_id identifier
		final Extension resourceOriginExtension = new Extension();
		resourceOriginExtension.setUrl(EXTENSION_URL);
		resourceOriginExtension.setValue(new StringType(resourceOrigin));

		domainResource.addExtension(resourceOriginExtension);
	}

}
