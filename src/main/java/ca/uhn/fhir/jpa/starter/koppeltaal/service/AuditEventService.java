package ca.uhn.fhir.jpa.starter.koppeltaal.service;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.api.model.DaoMethodOutcome;
import ca.uhn.fhir.jpa.starter.koppeltaal.dto.AuditEventDto;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.hl7.fhir.r4.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 *
 */
@Component
public class AuditEventService {
  protected long sleepTime = 2000;
  private static final Logger LOG = LoggerFactory.getLogger(AuditEventService.class);
  private final IFhirResourceDao<AuditEvent> auditEventDao;
  private final AuditEventBuilder auditEventBuilder;
  protected ExecutorService executorService;

  public AuditEventService(DaoRegistry daoRegistry, AuditEventBuilder auditEventBuilder) {
    this.auditEventDao = daoRegistry.getResourceDao(AuditEvent.class);
    this.auditEventBuilder = auditEventBuilder;
  }

  protected void setExecutorService(ExecutorService executorService) {
    this.executorService = executorService;
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
        Thread.sleep(sleepTime); //introduce a delay as this runs async and can cause a concurrency issue where the referenced entity doesn't exist yet, causing referential integrity issues
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
