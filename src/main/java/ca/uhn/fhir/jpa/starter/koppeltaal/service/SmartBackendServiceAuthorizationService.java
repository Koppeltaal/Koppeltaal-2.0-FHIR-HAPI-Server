package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.jpa.starter.koppeltaal.config.SmartBackendServiceConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuthorizationDto;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.CrudOperation;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.FhirResourceType;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.PermissionDto;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus.Series;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class SmartBackendServiceAuthorizationService {

	private static final Logger LOG = LoggerFactory.getLogger(SmartBackendServiceAuthorizationService.class);

	private final RestTemplate restTemplate;
	private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;
	private final HttpEntity<AuthorizationDto> requestEntity;
	private Map<String, AuthorizationDto> deviceIdToAuthorizationMap = new HashMap<>();

	public SmartBackendServiceAuthorizationService(SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
		this.restTemplate = new RestTemplate();

		HttpHeaders authorizationHeader = new HttpHeaders();
		authorizationHeader.set("X-Auth-Token", smartBackendServiceConfiguration.getAuthorizationToken());

		 requestEntity = new HttpEntity<>(authorizationHeader);
	}

	public List<PermissionDto> getPermissions(String deviceId) {
		return deviceIdToAuthorizationMap.containsKey(deviceId) ? deviceIdToAuthorizationMap.get(deviceId).getPermissions() : new ArrayList<>();
	}

	public Optional<PermissionDto> getPermission(String deviceId, RequestDetails requestDetails) {

		final CrudOperation operation = getCrudOperation(requestDetails.getRequestType());
		final FhirResourceType resourceType = FhirResourceType.fromResourceName(requestDetails.getResourceName());

		return getPermissions(deviceId).stream()
			.filter((permission -> permission.getOperation() == operation && permission.getResourceType() == resourceType))
			.findAny();
	}

	@Scheduled(fixedDelay = 1000 * 30)
	public void refreshAuthorizations() {

		try  {
			final String url = smartBackendServiceConfiguration.getBaseUrl() + "/authorization";
			LOG.trace("Getting authorizations from URL [{}]", url);
			final ResponseEntity<AuthorizationDto[]> response = restTemplate.exchange(url, HttpMethod.GET,
				requestEntity, AuthorizationDto[].class);
			LOG.debug("Gotten authorizations from URL [{}]", url);

			if(response.getStatusCode().series() == Series.SUCCESSFUL && response.getBody() != null) {
				setAuthorizationDtos(Arrays.asList(response.getBody()));
				LOG.debug("Updated authorizations");
			} else {
				LOG.warn("Unable to retrieve authorizations fom the SMART Backend Service at URL [{}]. Status code [{}].", url,
					response.getStatusCodeValue());
			}
		} catch (Exception e) {
			LOG.error("Failed to refresh authorizations.", e);
		}
	}

	private void setAuthorizationDtos(List<AuthorizationDto> authorizationDtos) {

		deviceIdToAuthorizationMap = authorizationDtos.stream()
			.filter(authorizationDto -> StringUtils.isNotBlank(authorizationDto.getDeviceId()))
			.collect(Collectors.toMap(AuthorizationDto::getDeviceId, Function.identity()));
	}

	private CrudOperation getCrudOperation(RequestTypeEnum requestType) {
		switch(requestType) {
			case GET: return CrudOperation.READ;
			case POST: return CrudOperation.CREATE;
			case PUT: return CrudOperation.UPDATE;
			case DELETE: return CrudOperation.DELETE;
			default:
				throw new UnsupportedOperationException(String.format(
					"Request type [%s] is not supported by the ResourceOriginAuthorizationInterceptor", requestType));
		}
	}
}
