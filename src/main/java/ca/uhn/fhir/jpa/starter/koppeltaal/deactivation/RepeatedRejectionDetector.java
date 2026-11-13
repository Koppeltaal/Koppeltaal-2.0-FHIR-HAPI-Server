package ca.uhn.fhir.jpa.starter.koppeltaal.deactivation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recognizes a client that keeps sending the same rejected requests, as opposed to one that has many different requests
 * rejected. Per client it counts rejected requests in a window that starts at the first rejection, and how many distinct
 * requests (fingerprints) they were. A client qualifies when the window holds at least {@code minRejections} rejections
 * and each distinct request came back at least {@code minRepeatFactor} times on average.
 */
public class RepeatedRejectionDetector {

	/**
	 * @param id stays the same across retries of the report, so domain-admin recognizes a detection it handled before
	 */
	public record Detection(String id, String clientId, int rejections, int distinctRequests, Instant windowStart,
			Instant detectedAt) {

		private static final DateTimeFormatter DUTCH_TIME =
				DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ROOT).withZone(ZoneId.of("Europe/Amsterdam"));

		/**
		 * The reason domain-admin shows and mails to Dutch readers, hence Dutch with Amsterdam times. Counts and times only,
		 * so it never names payloads or resources.
		 */
		public String reason() {
			String requests = distinctRequests <= 1 ? "steeds hetzelfde verzoek"
					: String.format(Locale.ROOT, "%d verschillende verzoeken, elk gemiddeld %d keer verstuurd",
							distinctRequests, rejections / distinctRequests);
			return String.format(Locale.ROOT, "%d geweigerde schrijfverzoeken tussen %s en %s (Nederlandse tijd): %s",
					rejections, DUTCH_TIME.format(windowStart), DUTCH_TIME.format(detectedAt), requests);
		}
	}

	private final Duration window;
	private final int minRejections;
	private final int minRepeatFactor;
	private final int maxFingerprints;
	private final Clock clock;
	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	public RepeatedRejectionDetector(Duration window, int minRejections, int minRepeatFactor, int maxFingerprints,
			Clock clock) {
		this.window = window;
		this.minRejections = minRejections;
		this.minRepeatFactor = minRepeatFactor;
		this.maxFingerprints = maxFingerprints;
		this.clock = clock;
	}

	/**
	 * Records one rejected request. A detection removes the client's window, so the next one needs a fresh window: at
	 * most one detection per window, also under concurrent calls.
	 */
	public Optional<Detection> record(String clientId, String fingerprint) {
		Instant now = clock.instant();
		Detection[] detection = new Detection[1];
		windows.compute(clientId, (client, current) -> {
			Window counted = current == null || current.hasEnded(now, window) ? new Window(now) : current;
			counted.add(fingerprint, maxFingerprints);
			if (counted.qualifies(minRejections, minRepeatFactor)) {
				detection[0] = new Detection(UUID.randomUUID().toString(), client, counted.rejections,
						counted.fingerprints.size(), counted.start, now);
				return null;
			}
			return counted;
		});
		return Optional.ofNullable(detection[0]);
	}

	/** Only touched inside {@link ConcurrentHashMap#compute}, which serializes the updates per client. */
	private static final class Window {

		private final Instant start;
		private final Set<String> fingerprints = new HashSet<>();
		private int rejections;
		private boolean varied;

		private Window(Instant start) {
			this.start = start;
		}

		private boolean hasEnded(Instant now, Duration window) {
			return !now.isBefore(start.plus(window));
		}

		private void add(String fingerprint, int maxFingerprints) {
			rejections++;
			if (varied) {
				return;
			}
			fingerprints.add(fingerprint);
			if (fingerprints.size() > maxFingerprints) {
				// many different requests are no repeat loop; stop tracking them until the window ends
				varied = true;
				fingerprints.clear();
			}
		}

		private boolean qualifies(int minRejections, int minRepeatFactor) {
			return !varied && rejections >= minRejections && rejections >= (long) minRepeatFactor * fingerprints.size();
		}
	}
}
