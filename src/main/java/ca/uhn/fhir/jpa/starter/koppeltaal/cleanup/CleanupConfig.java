package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.svc.IDeleteExpungeSvc;
import ca.uhn.fhir.jpa.api.svc.IIdHelperService;
import ca.uhn.fhir.jpa.model.dao.JpaPid;
import ca.uhn.fhir.jpa.util.MemoryCacheService;
import ca.uhn.fhir.jpa.starter.koppeltaal.config.FhirServerAuditLogConfiguration;
import ca.uhn.fhir.jpa.starter.koppeltaal.interceptor.DeleteFlowLifecycleAuditInterceptor;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name = "cleanup.enabled", havingValue = "true")
public class CleanupConfig {

	private static final Logger LOG = LoggerFactory.getLogger(CleanupConfig.class);

	@Bean
	public ParticipatingApps participatingApps(DaoRegistry daoRegistry,
			FhirServerAuditLogConfiguration auditLogConfiguration) {

		return new ParticipatingApps(daoRegistry,
				auditLogConfiguration.getObserver().getIdentifier().getSystem(),
				auditLogConfiguration.getObserver().getIdentifier().getValue());
	}

	@Bean
	public AnnouncementFactory announcementFactory(CleanupProperties cleanupProperties) {
		return new AnnouncementFactory(cleanupProperties, Clock.systemUTC());
	}

	@Bean
	public CleanupService cleanupService(DaoRegistry daoRegistry, CleanupProperties cleanupProperties,
			FhirServerAuditLogConfiguration auditLogConfiguration, ParticipatingApps participatingApps,
			AnnouncementFactory announcementFactory) {

		EligibilityService eligibilityService = new EligibilityService(daoRegistry, cleanupProperties,
				Clock.systemUTC());
		return new AnnouncementCleanupService(daoRegistry, participatingApps, eligibilityService,
				announcementFactory, auditLogConfiguration.getSite());
	}

	/**
	 * Spring exposes an EntityManagerFactory, not an EntityManager, so the
	 * transaction-bound one is derived from it.
	 */
	@Bean
	public PatientDeletionScope patientDeletionScope(EntityManagerFactory entityManagerFactory) {
		return new PatientDeletionScope(
				SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory));
	}

	@Bean
	public ResourceEraser resourceEraser(IDeleteExpungeSvc<JpaPid> deleteExpungeSvc,
			IIdHelperService<JpaPid> idHelper, EntityManagerFactory entityManagerFactory,
			MemoryCacheService memoryCacheService) {

		return new ResourceEraser(deleteExpungeSvc, idHelper,
				SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), memoryCacheService);
	}

	@Bean
	public DeleteFlowInventory deleteFlowInventory(DataSource dataSource) {
		return new DeleteFlowInventory(dataSource);
	}

	@Bean
	public ErasureCleanupService erasureCleanupService(DaoRegistry daoRegistry, CleanupProperties cleanupProperties,
			AnnouncementFactory announcementFactory, PatientDeletionScope patientDeletionScope,
			ParticipatingApps participatingApps, DeleteFlowAggregateLock deleteFlowAggregateLock,
			ResourceEraser resourceEraser, DeleteFlowInventory deleteFlowInventory, PlatformTransactionManager transactionManager,
			EntityManagerFactory entityManagerFactory, FhirServerAuditLogConfiguration auditLogConfiguration) {

		return new ErasureCleanupService(daoRegistry, cleanupProperties,
				new EligibilityService(daoRegistry, cleanupProperties, Clock.systemUTC()), announcementFactory,
				patientDeletionScope, participatingApps, deleteFlowAggregateLock, resourceEraser, deleteFlowInventory, transactionManager,
				SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory),
				auditLogConfiguration.getSite());
	}

	@Bean
	public DeleteFlowAggregateLock deleteFlowAggregateLock(ObjectProvider<EntityManagerFactory> entityManagerFactory) {

		EntityManagerFactory availableFactory = entityManagerFactory.getIfAvailable();

		if (availableFactory == null) {
			LOG.warn("No EntityManagerFactory available, so the aggregated cleanup lifecycle events are not "
					+ "serialised per patient; concurrent status writes may miss an unhold");
			return DeleteFlowAggregateLock.NONE;
		}
		return new AdvisoryDeleteFlowAggregateLock(SharedEntityManagerCreator.createSharedEntityManager(availableFactory));
	}

	@Bean
	public DeleteFlowLifecycleAuditInterceptor deleteFlowLifecycleAuditInterceptor(DaoRegistry daoRegistry,
			AnnouncementFactory announcementFactory, ParticipatingApps participatingApps,
			DeleteFlowAggregateLock deleteFlowAggregateLock, PlatformTransactionManager transactionManager,
			FhirServerAuditLogConfiguration auditLogConfiguration) {

		return new DeleteFlowLifecycleAuditInterceptor(daoRegistry, announcementFactory, participatingApps,
				deleteFlowAggregateLock, transactionManager, auditLogConfiguration.getSite());
	}
}
