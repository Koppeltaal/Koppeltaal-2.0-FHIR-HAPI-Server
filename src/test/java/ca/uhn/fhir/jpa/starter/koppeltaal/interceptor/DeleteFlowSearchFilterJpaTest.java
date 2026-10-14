package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.test.BaseJpaR4Test;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.SearchTotalModeEnum;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.SystemRequestDetails;
import ca.uhn.fhir.rest.param.StringParam;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the assumption the Task search narrowing rests on: HAPI evaluates one {@code _filter} that ORs the
 * matrix origins with the caller's own delete-pending Task, in a single query, so totals and paging hold.
 * Plain search parameters cannot express this; different parameters are always intersected.
 */
class DeleteFlowSearchFilterJpaTest extends BaseJpaR4Test {

	private static final String ORIGIN = "http://koppeltaal.nl/fhir/StructureDefinition/resource-origin";
	private static final String NARROWING = "resource-origin eq Device/app-a or "
			+ "(code eq http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code|delete-pending and owner eq Device/me)";

	private boolean filterEnabledBefore;
	private String normalA;
	private String dpMine;
	private String dpMineWithoutOrigin;

	@BeforeEach
	void createFixture() {
		filterEnabledBefore = myStorageSettings.isFilterParameterEnabled();
		myStorageSettings.setFilterParameterEnabled(true);

		SearchParameter resourceOrigin = new SearchParameter();
		resourceOrigin.setUrl("http://koppeltaal.nl/fhir/SearchParameter/resource-origin-extension");
		resourceOrigin.setCode("resource-origin");
		resourceOrigin.setStatus(Enumerations.PublicationStatus.ACTIVE);
		resourceOrigin.setType(Enumerations.SearchParamType.REFERENCE);
		resourceOrigin.addBase("Task").addBase("AuditEvent");
		resourceOrigin.setExpression("Task.extension('" + ORIGIN + "') | AuditEvent.extension('" + ORIGIN + "')");
		resourceOrigin.addTarget("Device");
		mySearchParameterDao.create(resourceOrigin, new SystemRequestDetails());
		mySearchParamRegistry.forceRefresh();

		for (String device : List.of("app-a", "app-c", "me", "other", "server")) {
			Device registration = new Device();
			registration.setId("Device/" + device);
			myDeviceDao.update(registration, new SystemRequestDetails());
		}

		normalA = task("app-a", null, Task.TaskStatus.READY);
		task("app-c", null, Task.TaskStatus.READY);
		dpMine = task("server", "me", Task.TaskStatus.REQUESTED);
		dpMineWithoutOrigin = task(null, "me", Task.TaskStatus.REQUESTED);
		task("server", "other", Task.TaskStatus.REQUESTED);
	}

	@AfterEach
	void restoreSettings() {
		myStorageSettings.setFilterParameterEnabled(filterEnabledBefore);
	}

	@Test
	void theGrantedOriginsAndOnlyTheCallersOwnDeletePendingTasksMatch() {
		assertThat(ids(search(NARROWING, null, null))).containsExactlyInAnyOrder(normalA, dpMine, dpMineWithoutOrigin);
	}

	@Test
	void theTotalCountsThoseMatchesAndTwoPagesHoldExactlyThem() {
		IBundleProvider firstPage = search(NARROWING, 2, 0);
		List<String> secondPage = ids(search(NARROWING, 2, 2));
		assertThat(firstPage.size()).isEqualTo(3);
		assertThat(secondPage).hasSize(1);
		assertThat(Stream.concat(ids(firstPage).stream(), secondPage.stream()))
				.containsExactlyInAnyOrder(normalA, dpMine, dpMineWithoutOrigin);
	}

	@Test
	void anApplicationFilterIsIntersectedWhenGroupedWithTheNarrowing() {
		String combined = "(status eq requested) and (" + NARROWING + ")";
		assertThat(ids(search(combined, null, null))).containsExactlyInAnyOrder(dpMine, dpMineWithoutOrigin);
	}

	private IBundleProvider search(String filter, Integer count, Integer offset) {
		SearchParameterMap map = new SearchParameterMap();
		map.add(Constants.PARAM_FILTER, new StringParam(filter));
		map.setSearchTotalMode(SearchTotalModeEnum.ACCURATE);
		SystemRequestDetails request = new SystemRequestDetails();
		if (count != null) {
			map.setCount(count);
			map.setOffset(offset);
			// BaseHapiFhirResourceDao.setOffsetAndCount reads the paging from the request as well.
			request.setParameters(Map.of(Constants.PARAM_COUNT, new String[] {count.toString()},
					Constants.PARAM_OFFSET, new String[] {offset.toString()}));
		}
		return myTaskDao.search(map, request);
	}

	private List<String> ids(IBundleProvider result) {
		return ids(result.getResources(0, Integer.MAX_VALUE));
	}

	private static List<String> ids(List<IBaseResource> resources) {
		return resources.stream().map(resource -> resource.getIdElement().getIdPart()).toList();
	}

	private String task(String origin, String deletePendingOwner, Task.TaskStatus status) {
		Task task = new Task().setStatus(status).setIntent(Task.TaskIntent.ORDER);
		if (origin != null) {
			task.addExtension(ORIGIN, new Reference("Device/" + origin));
		}
		if (deletePendingOwner != null) {
			task.getMeta().addSecurity("http://vzvz.nl/fhir/CodeSystem/koppeltaal-security-label", "kt2-delete-flow", null);
			task.setCode(new CodeableConcept(new Coding("http://vzvz.nl/fhir/CodeSystem/koppeltaal-task-code",
					"delete-pending", null)));
			task.setOwner(new Reference("Device/" + deletePendingOwner));
		}
		return myTaskDao.create(task, new SystemRequestDetails()).getId().getIdPart();
	}
}
