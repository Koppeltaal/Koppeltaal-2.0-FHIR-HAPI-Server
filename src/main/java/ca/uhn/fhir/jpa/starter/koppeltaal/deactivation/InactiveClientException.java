package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;

/**
 * The requesting client is deactivated, suspended, rejected, pending or deleted in domain-admin, or blocked here until
 * domain-admin confirms its deactivation.
 */
public class InactiveClientException extends ForbiddenOperationException {

	public InactiveClientException() {
		super("Client is not active");
	}
}
