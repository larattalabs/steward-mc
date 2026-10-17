package dev.larattalabs.steward.service;

import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.SiteEvents;
import dev.larattalabs.steward.gateway.PlanWriterJob;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Runs the plan writer (docs/COMPOSITION.md, plan v0) through Architect's Jobs API. Completion arrives as {@code JOB_DONE} on the server thread. A job that
 * finished before it was registered is picked up from {@code Jobs.get}. Server thread.
 */
public final class PlanWriter {
	private static final Map<String, CompletableFuture<PlanWriterJob.Result>> PENDING = new ConcurrentHashMap<>();
	private static boolean registered;

	private PlanWriter() {
	}

	/** Composes a plan for {@code card} (JSON). Fails at once when Architect's Claude link is not up. */
	public static CompletableFuture<PlanWriterJob.Result> write(String card, @Nullable String land, List<String> anchors, @Nullable String owner) {
		return write(card, land, anchors, List.of(), owner, null, null);
	}

	/** A repair round: {@code previous} the last plan, {@code answers} Architect's check of it (see {@link PlanWriterJob#build}). */
	public static CompletableFuture<PlanWriterJob.Result> write(String card, @Nullable String land, List<String> anchors, List<String> builds, @Nullable String owner,
		@Nullable String previous, @Nullable String answers) {
		return run(refs -> PlanWriterJob.build(card, land, anchors, builds, owner, previous, answers, refs));
	}

	/**
	 * A part of the kept plan that waited for Architect, written now against the town as it stands (docs/COMPOSITION.md section 11; see
	 * {@link PlanWriterJob#part}); a repair round when {@code previous} and {@code answers} are given.
	 */
	public static CompletableFuture<PlanWriterJob.Result> writePart(String card, String keptPlan, String partJson, String block, List<String> others,
		@Nullable String owner, @Nullable String previous, @Nullable String answers) {
		return run(refs -> PlanWriterJob.part(card, keptPlan, partJson, block, others, owner, previous, answers, refs));
	}

	private static CompletableFuture<PlanWriterJob.Result> run(java.util.function.Function<Boolean, dev.larattalabs.architect.api.JobSpec> spec) {
		var jobs = ArchitectApi.get().jobs();
		if (!registered) {
			SiteEvents.JOB_DONE.register(PlanWriter::onDone);
			registered = true;
		}
		if (!jobs.available()) return CompletableFuture.failedFuture(new IllegalStateException("Architect's Claude link is not available."));
		CompletableFuture<PlanWriterJob.Result> out = new CompletableFuture<>();
		// the schema by reference where the helper has it (C33): the helper's own copy, with the live catalogue
		boolean refs = ArchitectApi.get().features().contains("job.systemRefs");
		dev.larattalabs.steward.Steward.LOGGER.info("plan writer: the schema {}", refs ? "by reference (plan/0#compact)" : "inline");
		jobs.run(spec.apply(refs)).whenComplete((id, err) -> {
			if (err != null) {
				out.completeExceptionally(err);
				return;
			}
			PENDING.put(id, out);
			jobs.get(id).filter(Job::finished).ifPresent(PlanWriter::onDone);
		});
		return out;
	}

	private static void onDone(Job job) {
		var f = PENDING.remove(job.id());
		if (f != null) f.complete(PlanWriterJob.interpret(job));
	}
}
