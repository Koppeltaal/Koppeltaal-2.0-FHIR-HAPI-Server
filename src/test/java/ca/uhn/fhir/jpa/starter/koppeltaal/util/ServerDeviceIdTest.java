package ca.uhn.fhir.jpa.starter.koppeltaal.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.starter.koppeltaal.cleanup.ParticipatingApps;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.Test;

class ServerDeviceIdTest {

	private final ParticipatingApps participatingApps = mock(ParticipatingApps.class);
	private final ServerDeviceId serverDeviceId = new ServerDeviceId(participatingApps);

	@Test
	void isEmptyUntilTheServerDeviceIsRegisteredAndThenLooksItUpOnlyOnce() {
		when(participatingApps.serverDeviceId())
				.thenThrow(new IllegalStateException("No server Device found"))
				.thenReturn(new IdType("Device", "server"));

		assertThat(serverDeviceId.get()).isEmpty();
		assertThat(serverDeviceId.get()).contains(new IdType("Device", "server"));
		assertThat(serverDeviceId.get()).contains(new IdType("Device", "server"));
		verify(participatingApps, times(2)).serverDeviceId();
	}
}
