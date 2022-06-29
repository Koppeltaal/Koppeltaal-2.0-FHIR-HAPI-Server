package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 */
@Component
public class AuditEventService {
	private static final Logger LOG = LoggerFactory.getLogger(AuditEventService.class);
	private final IFhirResourceDao<AuditEvent> auditEventDao;
	private final AuditEventBuilder auditEventBuilder;
	private ExecutorService executorService;

	public AuditEventService(IFhirResourceDao<AuditEvent> auditEventDao, AuditEventBuilder auditEventBuilder) {
		this.auditEventDao = auditEventDao;
		this.auditEventBuilder = auditEventBuilder;
	}

	@PostConstruct
	public void init() {
		executorService = Executors.newCachedThreadPool();
	}

	@PreDestroy
	public void shutdown() {
		if (executorService != null) {
			executorService.shutdown();
		}
	}

	public void submitAuditEvent(AuditEventDto dto, RequestDetails requestDetails) {
		final AuditEvent auditEvent = auditEventBuilder.build(dto);
		executorService.submit(() -> {
      try {

        DaoMethodOutcome outcome = auditEventDao.create(auditEvent, requestDetails);
        if (!outcome.getCreated()) {
          LOG.warn("Unexpected outcome");
        }
      } catch (Throwable e) {
        LOG.warn("Unexpected exception", e);
      }
    });
	}

}
