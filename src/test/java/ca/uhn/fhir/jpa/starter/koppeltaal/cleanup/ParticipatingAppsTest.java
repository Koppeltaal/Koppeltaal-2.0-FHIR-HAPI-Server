package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.jpa.api.dao.IFhirResourceDao;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ParticipatingAppsTest {

	private static final String CLIENT_ID_SYSTEM = "http://vzvz.nl/fhir/NamingSystem/koppeltaal-client-id";

	@Mock private DaoRegistry daoRegistry;
	@Mock private IFhirResourceDao<Device> deviceDao;

	private ParticipatingApps participatingApps;

	private static Device device(String id, String clientIdIdentifier) {
		Device device = new Device();
		device.setId("Device/" + id);
		device.setStatus(Device.FHIRDeviceStatus.ACTIVE);
		device.addIdentifier(new Identifier().setSystem(CLIENT_ID_SYSTEM).setValue(clientIdIdentifier));
		return device;
	}

	@BeforeEach
	void setUp() {
		when(daoRegistry.getResourceDao(Device.class)).thenReturn(deviceDao);
		participatingApps = new ParticipatingApps(daoRegistry, "koppeltaal-fhir", "koppeltaal-server-001");
	}

	@Test
	void participatingDeviceIdsAppliesTheClientIdGate() {
		Device real = device("app-1", "app-1");
		Device legacyRelic = device("device-uuid-x", "some-other-client-id");
		when(deviceDao.search(any(), any())).thenReturn(new SimpleBundleProvider(List.of(real, legacyRelic)));

		List<String> ids = participatingApps.participatingDeviceIds().stream()
				.map(id -> id.getIdPart()).collect(Collectors.toList());

		assertEquals(List.of("app-1"), ids);
	}

	@Test
	void serverDeviceIdFindsTheObserverDevice() {
		Device serverDevice = new Device();
		serverDevice.setId("Device/server-device-1");
		when(deviceDao.search(any(), any())).thenReturn(new SimpleBundleProvider(List.of(serverDevice)));

		assertEquals("server-device-1", participatingApps.serverDeviceId().getIdPart());
	}

	@Test
	void serverDeviceIdThrowsWhenMissing() {
		when(deviceDao.search(any(), any())).thenReturn(new SimpleBundleProvider(List.of()));

		assertThrows(IllegalStateException.class, () -> participatingApps.serverDeviceId());
	}
}
