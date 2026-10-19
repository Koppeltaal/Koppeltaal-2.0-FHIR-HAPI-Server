package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.jpa.api.config.JpaStorageSettings;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.jpa.dao.predicate.SearchFilterParser;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.DeleteFlowTaskUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.PermissionUtil;
import ca.uhn.fhir.jpa.starter.koppeltaal.util.ResourceOriginUtil;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RequestTypeEnum;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.interceptor.auth.SearchNarrowingInterceptor;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Device;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Not extending the {@link SearchNarrowingInterceptor} as it only works with
 * default {@link
 * org.hl7.fhir.CompartmentDefinition} objects.
 * <p>
 * The kt2-delete-flow marker adds to an OWN or GRANTED search: a Task search also returns the caller's own
 * delete-pending Tasks, an AuditEvent search the delete-flow events the server records. The Task case needs an
 * OR across two parameters, which only {@code _filter} can express.
 * </p>
 */
@Interceptor
public class ResourceOriginSearchNarrowingInterceptor extends BaseAuthorizationInterceptor {

	private final JpaStorageSettings storageSettings;
	private final Supplier<Optional<IIdType>> serverDeviceId;

	public ResourceOriginSearchNarrowingInterceptor(DaoRegistry daoRegistry,
			IFhirResourceDao<Device> deviceDao, JpaStorageSettings storageSettings,
			Supplier<Optional<IIdType>> serverDeviceId) {

		super(daoRegistry, deviceDao);
		this.storageSettings = storageSettings;
		this.serverDeviceId = serverDeviceId;
	}

	@Hook(Pointcut.SERVER_INCOMING_REQUEST_POST_PROCESSED)
	protected void narrowSearch(RequestDetails requestDetails) {

		final String resourceName = requestDetails.getResourceName();

		if (requestDetails.getRequestType() != RequestTypeEnum.GET || StringUtils.isBlank(resourceName))
			return;
		else if (requestDetails.getRequestType() == RequestTypeEnum.GET
				&& "ImplementationGuide".equals(requestDetails.getResourceName()))
			return;

		List<String> relevantScopes = PermissionUtil.getScopesForRequest(requestDetails);

		if (relevantScopes.isEmpty())
			return; // no permission, not handled by this interceptor

		Set<String> resourceOrigins = PermissionUtil.getResourceOrigins(relevantScopes);

		if (resourceOrigins.isEmpty())
			return; // ALL permission, query already valid

		final Map<String, String[]> originalParameters = requestDetails.getParameters();
		final HashMap<String, String[]> narrowedSearchParameters = new HashMap<>(originalParameters);

		// If resource-origins are found, this means the permission is either OWN or
		// GRANTED
		final Optional<String> ownDeletePendingTasks = ownDeletePendingTasks(requestDetails);
		if (ownDeletePendingTasks.isPresent()) {
			narrowedSearchParameters.remove("resource-origin");
			narrowedSearchParameters.put(Constants.PARAM_FILTER, new String[] {withApplicationFilters(
					originalParameters.get(Constants.PARAM_FILTER),
					originFilter(resourceOrigins) + " or " + ownDeletePendingTasks.get())});
		} else {
			narrowedSearchParameters.put("resource-origin",
					new String[] { String.join(",", withDeleteFlowEvents(resourceName, resourceOrigins)) });
		}

		requestDetails.setParameters(narrowedSearchParameters);
	}

	private Optional<String> ownDeletePendingTasks(RequestDetails requestDetails) {
		if (!"Task".equals(requestDetails.getResourceName())
				|| requestDetails.getRestOperationType() != RestOperationTypeEnum.SEARCH_TYPE
				|| !storageSettings.isFilterParameterEnabled()) {
			return Optional.empty();
		}
		return ResourceOriginUtil.getRequesterClientId(requestDetails).map(clientId -> String.format(
				"(code eq %s|%s and owner eq Device/%s)",
				DeleteFlowTaskUtil.TASK_CODE_SYSTEM, DeleteFlowTaskUtil.DELETE_PENDING_CODE, clientId));
	}

	private static String originFilter(Set<String> resourceOrigins) {
		return new TreeSet<>(resourceOrigins).stream()
				.map(origin -> "resource-origin eq " + origin)
				.collect(Collectors.joining(" or "));
	}

	/**
	 * HAPI applies only the first {@code _filter}, so an application's own filters are folded into one
	 * expression with the narrowing. Each must parse on its own first: a filter that closes its group early
	 * would otherwise escape the narrowing.
	 */
	private static String withApplicationFilters(String[] applicationFilters, String narrowing) {
		if (applicationFilters == null || applicationFilters.length == 0) {
			return narrowing;
		}
		for (String applicationFilter : applicationFilters) {
			try {
				SearchFilterParser.parse(applicationFilter);
			} catch (SearchFilterParser.FilterSyntaxException e) {
				throw new InvalidRequestException("Error parsing _filter syntax: " + e.getMessage());
			}
		}
		return Arrays.stream(applicationFilters).map(filter -> "(" + filter + ")").collect(Collectors.joining(" and "))
				+ " and (" + narrowing + ")";
	}

	/** Only the delete-flow events have the server's own Device as resource-origin. */
	private Set<String> withDeleteFlowEvents(String resourceName, Set<String> resourceOrigins) {
		final Set<String> origins = new LinkedHashSet<>(resourceOrigins);
		if ("AuditEvent".equals(resourceName)) {
			serverDeviceId.get().ifPresent(server -> origins.add("Device/" + server.getIdPart()));
		}
		return origins;
	}
}
