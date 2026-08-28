package ca.uhn.fhir.jpa.starter.koppeltaal.cleanup;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of one cleanup run. Timestamps are exposed as ISO-8601
 * strings so JSON serialization needs no time-module configuration.
 */
public class CleanupRunReport {

	public enum RunStatus {
		RUNNING,
		COMPLETED,
		FAILED
	}

	private final RunStatus status;
	private final String startedAt;
	private final String finishedAt;
	private final int patientsEvaluated;
	private final int patientsEligible;
	private final int tasksCreated;
	private final int auditEventsCreated;
	private final List<String> errors;
	private final boolean dryRun;
	private final int patientsTotal;

	public CleanupRunReport(RunStatus status, Instant startedAt, Instant finishedAt, int patientsEvaluated,
			int patientsEligible, int tasksCreated, int auditEventsCreated, List<String> errors, boolean dryRun) {
		this(status, startedAt, finishedAt, patientsEvaluated, patientsEligible, tasksCreated, auditEventsCreated,
				errors, dryRun, 0);
	}

	public CleanupRunReport(RunStatus status, Instant startedAt, Instant finishedAt, int patientsEvaluated,
			int patientsEligible, int tasksCreated, int auditEventsCreated, List<String> errors, boolean dryRun,
			int patientsTotal) {
		this.status = status;
		this.startedAt = startedAt == null ? null : startedAt.toString();
		this.finishedAt = finishedAt == null ? null : finishedAt.toString();
		this.patientsEvaluated = patientsEvaluated;
		this.patientsEligible = patientsEligible;
		this.tasksCreated = tasksCreated;
		this.auditEventsCreated = auditEventsCreated;
		this.errors = errors == null ? Collections.emptyList() : List.copyOf(errors);
		this.dryRun = dryRun;
		this.patientsTotal = patientsTotal;
	}

	public static CleanupRunReport running(Instant startedAt, boolean dryRun) {
		return new CleanupRunReport(RunStatus.RUNNING, startedAt, null, 0, 0, 0, 0, Collections.emptyList(), dryRun);
	}

	public static CleanupRunReport failed(Instant startedAt, String error, boolean dryRun) {
		return new CleanupRunReport(RunStatus.FAILED, startedAt, Instant.now(), 0, 0, 0, 0,
				error == null ? Collections.emptyList() : List.of(error), dryRun);
	}

	public RunStatus getStatus() {
		return status;
	}

	public String getStartedAt() {
		return startedAt;
	}

	public String getFinishedAt() {
		return finishedAt;
	}

	public int getPatientsEvaluated() {
		return patientsEvaluated;
	}

	public int getPatientsEligible() {
		return patientsEligible;
	}

	public int getTasksCreated() {
		return tasksCreated;
	}

	public int getAuditEventsCreated() {
		return auditEventsCreated;
	}

	public List<String> getErrors() {
		return errors;
	}

	public int getPatientsTotal() {
		return patientsTotal;
	}

	public boolean isDryRun() {
		return dryRun;
	}
}
