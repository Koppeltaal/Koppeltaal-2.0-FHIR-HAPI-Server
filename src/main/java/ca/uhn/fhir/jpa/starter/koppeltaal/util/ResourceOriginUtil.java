package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DomainResource;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StructureDefinition;

public class ResourceOriginUtil {

	public final static String RESOURCE_ORIGIN_SYSTEM = "http://koppeltaal.nl/resource-origin";

	public static Optional<String> getRequesterClientId(RequestDetails requestDetails) {
		String authorization = requestDetails.getHeader("Authorization");
		String token = StringUtils.trim(StringUtils.removeStartIgnoreCase(authorization, "Bearer"));

		if(StringUtils.isBlank(token)) return Optional.empty();

		// Already validated by the JwtSecurityInterceptor
		DecodedJWT decode = JWT.decode(token);

		final Claim azpClaim = decode.getClaim("azp");
		String resourceOrigin = azpClaim.asString();

		return StringUtils.isBlank(resourceOrigin) ? Optional.empty() : Optional.of(resourceOrigin);
	}

	public static Optional<IIdType> getResourceOriginDeviceId(IBaseResource resource) {
		if(!(resource instanceof DomainResource)) return Optional.empty();

		final Extension extension = ((DomainResource) resource).getExtensionByUrl(RESOURCE_ORIGIN_SYSTEM);

		if(extension == null || extension.isEmpty()) return Optional.empty();

		final IIdType reference = ((Reference) extension.getValue()).getReferenceElement();
		return Optional.of(reference);
	}

	public static Optional<Device> getDevice(RequestDetails requestDetails, IFhirResourceDao<Device> deviceDao) {

		final Optional<String> clientIdOptional = getRequesterClientId(requestDetails);
		if(!clientIdOptional.isPresent()) return Optional.empty();

		return getDevice(clientIdOptional.get(), deviceDao);
	}

	private static Optional<Device> getDevice(String clientId, IFhirResourceDao<Device> deviceDao) {
		final SearchParameterMap searchParameterMap = new SearchParameterMap();
		searchParameterMap.add(StructureDefinition.SP_IDENTIFIER, new TokenParam(clientId));

		final IBundleProvider searchResults = deviceDao.search(searchParameterMap);

		if(searchResults != null && !searchResults.isEmpty()) {
			return Optional.of((Device) searchResults.getAllResources().get(0));
		}

		return Optional.empty();
	}
}
