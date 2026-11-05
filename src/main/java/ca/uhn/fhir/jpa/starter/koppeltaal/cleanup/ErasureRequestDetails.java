package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import ca.uhn.fhir.rest.api.server.SystemRequestDetails;

/**
 * The erase run's write to a CareTeam it keeps. Such a CareTeam can still name what an earlier erase removed, since a
 * blocked resource keeps its references, so for this write a reference that no longer resolves costs its index row
 * rather than the write; see {@code DaoResourceLinkResolverOverride}.
 */
public class ErasureRequestDetails extends SystemRequestDetails {
}
