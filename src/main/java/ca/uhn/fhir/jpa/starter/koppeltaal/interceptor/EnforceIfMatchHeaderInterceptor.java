package ca.uhn.fhir.jpa.starter.koppeltaal.interceptor;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException;
import org.apache.commons.lang3.StringUtils;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Interceptor that makes sure the <code>If-Match</code> header is set whilst updating resources
 */
@Interceptor
public class EnforceIfMatchHeaderInterceptor {
	private static final Logger LOG = LoggerFactory.getLogger(EnforceIfMatchHeaderInterceptor.class);

	@Hook(Pointcut.STORAGE_PRESTORAGE_RESOURCE_UPDATED)
	protected void ensureIfMatchIsSet(IBaseResource previousResource, RequestDetails requestDetails) {

		if(requestDetails.getRestOperationType() == RestOperationTypeEnum.TRANSACTION) {
			//Transactional Bundle, cannot simply get the If-Match header
			handleTransactionalBundle(previousResource, requestDetails);
		} else {
			throwExceptionWhenIfMatchNotSet(requestDetails.getHeader("If-Match"));
		}
	}

	/**
     * A transactional bundle can contain multiple requests. The request information is stored inside the
	 * {@link Bundle.BundleEntryComponent}.
	 *
	 * @param previousResource
	 * @param requestDetails
	 */
	private void handleTransactionalBundle(IBaseResource previousResource, RequestDetails requestDetails) {

		//FIXME: There is probably a better way to get the Bundle.entry.request in HAPI via the interceptor-mechanism
		List<Bundle.BundleEntryComponent> updateRequestsFromBundle = ((Bundle) requestDetails.getResource()).getEntry().stream()
			.filter(
				entry -> entry.getRequest().getMethod() == Bundle.HTTPVerb.PUT &&
					StringUtils.equals(
						entry.getResource().getIdElement().toUnqualifiedVersionless().getValue(),
						previousResource.getIdElement().toUnqualifiedVersionless().getValue()
					)
			).collect(Collectors.toList());

		if(updateRequestsFromBundle.isEmpty()) {
			throw new InternalErrorException("Unable to map resource from database to resource in transactional Bundle inside the EnforceIfMatchHeaderInterceptor");
		}

		//Transactional/batch requests only allow one action per resource and version, should be no need to iterate over the results
		updateRequestsFromBundle.forEach(bundleEntry ->
			throwExceptionWhenIfMatchNotSet(bundleEntry.getRequest().getIfMatch())
		);
	}

	private void throwExceptionWhenIfMatchNotSet(String ifMatchHeaderValue) {
		if(StringUtils.isBlank(ifMatchHeaderValue)) {
			LOG.warn("Received Resource update without an If-Match header");
			throw new PreconditionFailedException("If-Match header is required on updates");
		}
	}
}
