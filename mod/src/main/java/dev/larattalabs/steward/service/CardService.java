package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.JobSpec;
import dev.larattalabs.architect.api.Jobs;
import dev.larattalabs.steward.gateway.CardResult;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the concept-card parse through Architect's Jobs API and hands back the result. Completion arrives as a {@code JOB_DONE} event on the
 * server thread, so futures from here complete there. Jobs are tracked by id; one that finished before we could register is picked up
 * from {@link Jobs#get}. The Architect-facing calls are injected so this is testable without a game.
 */
public final class CardService {
	private final Jobs jobs;
	private final Map<String, CompletableFuture<CardResult>> pending = new ConcurrentHashMap<>();

	public CardService(Jobs jobs) {
		this.jobs = jobs;
	}

	/** Starts a parse. Fails fast (with a message the player can read) when Architect's Claude link is not up. */
	public CompletableFuture<CardResult> submit(String prompt, Map<String, String> chips, ConceptCardJob.Settings settings, String owner) {
		if (!jobs.available()) {
			return CompletableFuture.failedFuture(new IllegalStateException(
				"Architect's Claude link is not available (the helper is not running, or it has no API key or login). Open Architect and check its status line."));
		}
		JobSpec spec;
		try {
			spec = ConceptCardJob.build(prompt, chips, settings, owner);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		CompletableFuture<CardResult> out = new CompletableFuture<>();
		jobs.run(spec).whenComplete((id, err) -> {
			if (err != null) {
				out.completeExceptionally(err);
				return;
			}
			pending.put(id, out);
			// the job may have finished between the ack and now
			jobs.get(id).filter(Job::finished).ifPresent(this::onDone);
		});
		return out;
	}

	/** JOB_DONE listener. Ignores jobs that are not ours. */
	public void onDone(Job job) {
		CompletableFuture<CardResult> f = pending.remove(job.id());
		if (f != null) f.complete(CardResult.interpret(job));
	}

	public int pendingCount() {
		return pending.size();
	}
}
