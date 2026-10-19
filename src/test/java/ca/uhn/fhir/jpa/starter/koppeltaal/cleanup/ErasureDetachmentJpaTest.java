package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.jpa.searchparam.extractor.SearchParamExtractorService;
import ca.uhn.fhir.jpa.starter.koppeltaal.bean.DaoResourceLinkResolverOverride;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import java.util.List;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A CareTeam that one erase keeps as blocked can still name the patient that erase removed. A later erase detaching
 * another participant rewrites the whole CareTeam, through HAPI's reference indexing and the KT2 resolver.
 */
class ErasureDetachmentJpaTest extends BaseJpaR4Test {

	private static final String RESOLVER = "myResourceLinkResolver";

	@Autowired
	private SearchParamExtractorService extractor;
	private Object hapiResolver;

	@BeforeEach
	void useTheKoppeltaalResolver() {
		hapiResolver = ReflectionTestUtils.getField(extractor, RESOLVER);
		ReflectionTestUtils.setField(extractor, RESOLVER,
				myAppCtx.getAutowireCapableBeanFactory().createBean(DaoResourceLinkResolverOverride.class));
	}

	@AfterEach
	void restoreHapisResolver() {
		ReflectionTestUtils.setField(extractor, RESOLVER, hapiResolver);
	}

	@Test
	void aLaterEraseDetachesAParticipantFromACareTeamThatNamesAnErasedPatient() {
		IIdType erased = create(new Patient());
		IIdType leaving = create(new Patient());
		CareTeam team = new CareTeam().setSubject(new Reference(erased));
		team.addParticipant().setMember(new Reference(leaving));
		IIdType teamId = myCareTeamDao.create(team, new SystemRequestDetails()).getId().toUnqualifiedVersionless();
		new TransactionTemplate(myTxManager).executeWithoutResult(status -> new ResourceEraser(myDeleteExpungeSvc,
				myIdHelperService, myEntityManager, myMemoryCacheService).erase(List.of(erased), List.of(), List.of(teamId)));

		CareTeam detached = myCareTeamDao.read(teamId, new SystemRequestDetails());
		detached.getParticipant().clear();

		assertThatThrownBy(() -> myCareTeamDao.update(detached.copy(), new SystemRequestDetails()))
				.isInstanceOf(InvalidRequestException.class).hasMessageContaining(erased.getIdPart());
		assertThatCode(() -> myCareTeamDao.update(detached, new ErasureRequestDetails())).doesNotThrowAnyException();
		CareTeam stored = myCareTeamDao.read(teamId, new SystemRequestDetails());
		assertThat(stored.getParticipant()).isEmpty();
		assertThat(stored.getSubject().getReference()).isEqualTo(erased.getValue());
	}

	private IIdType create(Patient patient) {
		return myPatientDao.create(patient, new SystemRequestDetails()).getId().toUnqualifiedVersionless();
	}
}
