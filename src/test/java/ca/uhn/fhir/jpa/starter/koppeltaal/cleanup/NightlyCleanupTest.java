package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@ExtendWith(MockitoExtension.class)
class NightlyCleanupTest {

	@Mock
	private ErasureCleanupService erasure;

	@Mock
	private CleanupService announcement;

	private boolean anotherServerRuns;

	private final CleanupRunLock runLock = cleanup -> {
		if (anotherServerRuns) {
			return false;
		}
		cleanup.run();
		return true;
	};

	@Test
	void judgesTheRunningCyclesBeforeAnnouncingNewOnes() {
		when(erasure.run(eq(false), any())).thenReturn(finished());
		when(announcement.run(eq(false), any())).thenReturn(finished());

		new NightlyCleanup(erasure, announcement, runLock).run();

		InOrder order = inOrder(erasure, announcement);
		order.verify(erasure).run(eq(false), any());
		order.verify(announcement).run(eq(false), any());
	}

	@Test
	void aFailedErasureRunStillAnnounces() {
		when(erasure.run(eq(false), any())).thenThrow(new IllegalStateException("database unreachable"));
		when(announcement.run(eq(false), any())).thenReturn(finished());

		new NightlyCleanup(erasure, announcement, runLock).run();

		verify(announcement).run(eq(false), any());
	}

	@Test
	void anotherServerRunningTheCleanupMeansThisOneSkips() {
		anotherServerRuns = true;

		new NightlyCleanup(erasure, announcement, runLock).run();

		verifyNoInteractions(erasure, announcement);
	}

	@Test
	void springSchedulesItAtTwoAtNight() {
		assertThat(cronExpressions(Map.of())).containsExactly("0 0 2 * * *");
	}

	@Test
	void aDashTurnsTheScheduleOff() {
		assertThat(cronExpressions(Map.of("cleanup.cron", "-"))).isEmpty();
	}

	private List<String> cronExpressions(Map<String, Object> properties) {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
			context.register(Scheduling.class);
			context.registerBean(NightlyCleanup.class, () -> new NightlyCleanup(erasure, announcement, runLock));
			context.refresh();
			return context.getBean(ScheduledTaskHolder.class).getScheduledTasks().stream()
					.map(ScheduledTask::getTask)
					.filter(CronTask.class::isInstance)
					.map(task -> ((CronTask) task).getExpression())
					.toList();
		}
	}

	@Configuration
	@EnableScheduling
	static class Scheduling {
	}

	private static CleanupRunReport finished() {
		return new CleanupRunReport(CleanupRunReport.RunStatus.COMPLETED, Instant.now(), Instant.now(), 0, 0, 0, 0,
				List.of(), false);
	}
}
