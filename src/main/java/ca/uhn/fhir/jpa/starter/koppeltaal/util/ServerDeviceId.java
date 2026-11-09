package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.ParticipatingApps;
import java.util.Optional;
import java.util.function.Supplier;
import org.hl7.fhir.instance.model.api.IIdType;

/** The Device the server acts as. It is registered after start-up, so it is looked up until it exists. */
public class ServerDeviceId implements Supplier<Optional<IIdType>> {

	private final ParticipatingApps participatingApps;
	private volatile IIdType found;

	public ServerDeviceId(ParticipatingApps participatingApps) {
		this.participatingApps = participatingApps;
	}

	@Override
	public Optional<IIdType> get() {
		if (found == null) {
			try {
				found = participatingApps.serverDeviceId();
			} catch (IllegalStateException notRegistered) {
				return Optional.empty();
			}
		}
		return Optional.of(found);
	}
}
