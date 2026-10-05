package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.Cost;
import dev.larattalabs.architect.api.Job;
import dev.larattalabs.architect.api.JobSpec;
import dev.larattalabs.architect.api.Jobs;
import dev.larattalabs.architect.api.ToolHandler;
import dev.larattalabs.steward.gateway.CardResult;
import dev.larattalabs.steward.gateway.ConceptCardJob;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CardServiceTest {
	private static final ConceptCardJob.Settings SETTINGS = new ConceptCardJob.Settings(false, "patron", 128);

	private static JsonObject realCard() throws Exception {
		var in = CardServiceTest.class.getClassLoader().getResourceAsStream("real/crater_works_real.json");
		return JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonObject("card");
	}

	private static Job job(String id, String status, Optional<JsonElement> result, Optional<String> error) {
		return new Job(id, new JsonObject(), status, "", result, error, new Cost(0.0187, 2, 583, 0, 3225, 2), 1L, 2L);
	}

	/** A fake Jobs: records specs, hands out ids, optionally knows a finished job already. */
	private static final class FakeJobs implements Jobs {
		boolean available = true;
		final List<JobSpec> specs = new ArrayList<>();
		Job known;

		public CompletableFuture<String> run(JobSpec s) { specs.add(s); return CompletableFuture.completedFuture("j1"); }
		public void cancel(String id) {}
		public Optional<Job> get(String id) { return Optional.ofNullable(known); }
		public List<Job> list(@Nullable String owner) { return List.of(); }
		public void registerTool(String o, String n, ToolHandler h) {}
		public boolean available() { return available; }
		public CompletableFuture<String> putBlob(String k, @Nullable String o, JsonElement d) { return CompletableFuture.completedFuture("b"); }
		public CompletableFuture<String> putBlob(String k, @Nullable String o, byte[] d) { return CompletableFuture.completedFuture("b"); }
	}

	@Test
	void doneEventCompletesTheFutureWithAParsedCard() throws Exception {
		FakeJobs jobs = new FakeJobs();
		CardService svc = new CardService(jobs);
		CompletableFuture<CardResult> f = svc.submit("a crater mine", Map.of(), SETTINGS, null);
		assertEquals(1, jobs.specs.size());
		assertEquals("concept-card", jobs.specs.get(0).tag());
		assertFalse(f.isDone());
		svc.onDone(job("j1", "done", Optional.of(realCard()), Optional.empty()));
		CardResult r = f.get();
		assertTrue(r.ok());
		assertEquals("giant meteor crater", r.card().site().text());
		assertEquals(0.0187, r.cost().usd(), 1e-9);
		assertEquals(0, svc.pendingCount());
	}

	@Test
	void aJobThatFinishedBeforeRegistrationIsPickedUp() throws Exception {
		FakeJobs jobs = new FakeJobs();
		jobs.known = job("j1", "done", Optional.of(realCard()), Optional.empty());
		CardResult r = new CardService(jobs).submit("x", Map.of(), SETTINGS, null).get();
		assertTrue(r.ok());
	}

	@Test
	void foreignJobsAreIgnored() throws Exception {
		FakeJobs jobs = new FakeJobs();
		CardService svc = new CardService(jobs);
		CompletableFuture<CardResult> f = svc.submit("x", Map.of(), SETTINGS, null);
		svc.onDone(job("other", "done", Optional.of(realCard()), Optional.empty()));
		assertFalse(f.isDone());
		assertEquals(1, svc.pendingCount());
	}

	@Test
	void failuresBecomeReadableErrors() throws Exception {
		CardService svc = new CardService(new FakeJobs());
		CompletableFuture<CardResult> f = svc.submit("x", Map.of(), SETTINGS, null);
		svc.onDone(job("j1", "failed", Optional.empty(), Optional.of("budget")));
		CardResult r = f.get();
		assertFalse(r.ok());
		assertTrue(r.error().contains("budget"));
	}

	@Test
	void anUnparseableResultIsAnErrorNotACrash() throws Exception {
		CardService svc = new CardService(new FakeJobs());
		CompletableFuture<CardResult> f = svc.submit("x", Map.of(), SETTINGS, null);
		JsonObject bad = new JsonObject();
		bad.addProperty("name", "no fields");
		svc.onDone(job("j1", "done", Optional.of(bad), Optional.empty()));
		assertFalse(f.get().ok());
	}

	@Test
	void refusesWhenArchitectsLinkIsDown() {
		FakeJobs jobs = new FakeJobs();
		jobs.available = false;
		CompletableFuture<CardResult> f = new CardService(jobs).submit("x", Map.of(), SETTINGS, null);
		ExecutionException e = assertThrows(ExecutionException.class, f::get);
		assertTrue(e.getCause().getMessage().contains("not available"));
		assertTrue(jobs.specs.isEmpty());
	}

	@Test
	void anEmptyPromptIsRefusedBeforeAnyJob() {
		FakeJobs jobs = new FakeJobs();
		CompletableFuture<CardResult> f = new CardService(jobs).submit("  ", Map.of(), SETTINGS, null);
		assertThrows(ExecutionException.class, f::get);
		assertTrue(jobs.specs.isEmpty());
	}

	@Test
	void linesNameEveryFieldAndTheCost() throws Exception {
		CardResult r = CardResult.interpret(job("j1", "done", Optional.of(realCard()), Optional.empty()));
		String all = String.join("\n", CardResult.lines(r.card(), r.cost()));
		assertTrue(all.contains("Site: giant meteor crater (custom)"));
		assertTrue(all.contains("[infernal]"));
		assertTrue(all.contains("Cost: $0.019"));
	}
}
