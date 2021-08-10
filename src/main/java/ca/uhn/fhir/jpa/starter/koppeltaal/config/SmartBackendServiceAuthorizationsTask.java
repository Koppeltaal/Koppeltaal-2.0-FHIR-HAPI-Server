package ca.uhn.fhir.jpa.starter.koppeltaal.config;

import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuthorizationDto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus.Series;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class SmartBackendServiceAuthorizationsTask {

	private static final Logger LOG = LoggerFactory.getLogger(SmartBackendServiceAuthorizationsTask.class);

	private final RestTemplate restTemplate;
	private final SmartBackendServiceConfiguration smartBackendServiceConfiguration;
	private final HttpEntity<AuthorizationDto> requestEntity;

	private List<AuthorizationDto> authorizationDtos = new ArrayList<>();

	public SmartBackendServiceAuthorizationsTask(SmartBackendServiceConfiguration smartBackendServiceConfiguration) {
		this.smartBackendServiceConfiguration = smartBackendServiceConfiguration;
		this.restTemplate = new RestTemplate();

		HttpHeaders authorizationHeader = new HttpHeaders();
		authorizationHeader.set("X-Auth-Token", smartBackendServiceConfiguration.getAuthorizationToken());

		 requestEntity = new HttpEntity<>(authorizationHeader);

		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				refreshAuthorizations();
			}
		};

		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		final ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(task, 0, 30, TimeUnit.SECONDS);

		try {
			scheduledFuture.get();
		} catch (Exception e) {
			throw new RuntimeException("Failed to execute [refreshAuthorizations] task.", e);
		}
	}

	private void refreshAuthorizations() {

		final String url = smartBackendServiceConfiguration.getBaseUrl() + "/authorization";
		LOG.info("Getting authorizations from URL [{}]", url);
		final ResponseEntity<AuthorizationDto[]> response = restTemplate.exchange(url, HttpMethod.GET,
			requestEntity, AuthorizationDto[].class);
		LOG.info("Gotten authorizations from URL [{}]", url);

		if(response.getStatusCode().series() == Series.SUCCESSFUL && response.getBody() != null) {
			this.authorizationDtos = Arrays.asList(response.getBody());
			LOG.trace("Updated authorizations");
		} else {
			LOG.warn("Unable to retrieve authorizations fom the SMART Backend Service at URL [{}]. Status code [{}].", url,
				response.getStatusCodeValue());
		}
	}

	public List<AuthorizationDto> getAuthorizationDtos() {
		return authorizationDtos;
	}
}
