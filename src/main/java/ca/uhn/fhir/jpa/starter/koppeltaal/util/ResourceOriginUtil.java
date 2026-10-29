package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.ResourceGoneException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;

import java.util.Optional;

public class ResourceOriginUtil {

  public final static String RESOURCE_ORIGIN_SYSTEM = "http://koppeltaal.nl/fhir/StructureDefinition/resource-origin";

  public static Optional<String> getRequesterClientId(RequestDetails requestDetails) {
    return PermissionUtil.findAccessToken(requestDetails)
      .map((accessToken) -> accessToken.getClaim("azp").asString())
      .filter(StringUtils::isNotBlank);
  }

  public static Optional<IIdType> getResourceOriginDeviceId(IBaseResource resource) {
    if (!(resource instanceof DomainResource)) return Optional.empty();

    final Extension extension = ((DomainResource) resource).getExtensionByUrl(RESOURCE_ORIGIN_SYSTEM);

    if (extension == null || extension.isEmpty()) return Optional.empty();

    final IIdType reference = ((Reference) extension.getValue()).getReferenceElement();
    return Optional.of(reference);
  }

  public static Optional<Device> getDevice(RequestDetails requestDetails, IFhirResourceDao<Device> deviceDao) {

    final Optional<String> clientIdOptional = getRequesterClientId(requestDetails);
    if (clientIdOptional.isEmpty()) return Optional.empty();

    return getDevice(clientIdOptional.get(), deviceDao, requestDetails);
  }

  private static Optional<Device> getDevice(String clientId, IFhirResourceDao<Device> deviceDao, RequestDetails requestDetails) {
    try {
      return Optional.of(
        deviceDao.read(new IdType(clientId), requestDetails)
      );
    } catch (ResourceNotFoundException | ResourceGoneException e) {
      return Optional.empty();
    }
  }
}
