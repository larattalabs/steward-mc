package dev.larattalabs.steward.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.larattalabs.architect.api.ArchitectApi;
import dev.larattalabs.architect.api.ArchitectRefused;
import dev.larattalabs.architect.api.Reason;
import dev.larattalabs.architect.api.RegionBuild;
import dev.larattalabs.architect.api.RegionState;
import dev.larattalabs.architect.api.RegionView;
import dev.larattalabs.architect.api.Regions;
import dev.larattalabs.architect.api.RemoveOptions;
import dev.larattalabs.architect.api.RemoveResult;
import dev.larattalabs.architect.api.RevisionView;
import dev.larattalabs.architect.api.SiteView;
import dev.larattalabs.architect.api.Sites;
import dev.larattalabs.architect.api.State;
import dev.larattalabs.architect.api.UnadoptResult;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * (slice GEA) A faked Architect for unit tests of Steward's recovery adapters (the reconcile, the growth sweep, {@code Undo.growth}), installed through
 * {@link ArchitectAccess#useForTest}. It models what those paths read and ask, as Architect's contract says it behaves: regions with their state, position,
 * busy note, revisions and {@code architect_mc:land}; sites with their state and region; {@code undoRevision}, {@code Regions.remove}, {@code unadopt} and
 * {@code Sites.remove} answers, refusals and lost answers included; {@code Sites.loadHealthy} and the kit and checker versions (slice BCU); and the stockpile ledger of a region built from materials (paid == returned).
 * (TESTABLE-ARCH) Also a composed town's realise: approved artifacts ({@code regions().artifact}, an empty {@code checkPalette}), the read-only quote
 * ({@code quoteApproved}, answered with {@link #quote}), and {@code realiseApproved}, each request recorded ({@link #approved}, its
 * {@code maxBillPerLot} included) and answered as {@link #realiseAnswer} says ({@code REFUSE}: {@code BILL_CAP} with the detail {@link #capDetail});
 * {@code designs().group} answers {@link #groups} (empty unless a test gives one), {@code library().get} empty. {@link #onCall} runs as each call is recorded, so a test can read the disk then.
 * Anything it does not model throws, so a path that reaches past the model fails loudly; every call is recorded ({@link #calls}).
 */
final class FakeArchitect {
	/** How the next world-changing call is answered. */
	enum Answer {
		/** Done, and answered so. */
		APPLY,
		/** Not done: answered not removed, with a blocker (nothing written). */
		BLOCKED,
		/** Not done: refused with an error (nothing written). */
		REFUSE,
		/** Done, but the answer is lost on its way (a timeout): the caller must read again to know. */
		APPLY_THEN_LOSE,
		/** Never answered (the call is in flight until {@link #complete} is run). */
		HOLD,
		/**
		 * (T4S-PRE, T4) Decided and held: the future fails at once with {@code SITE_BUSY} detail {@code held(decided)} (the place, {@link #heldWhere},
		 * in the message), and the removal finishes later, when {@link #complete} runs (as Architect's held txn retires).
		 */
		HELD_DECIDED,
		/** (T4S-PRE, T4 change 5c) Another txn holds a resource: {@code SITE_BUSY held(other)}, nothing done, {@link #otherTimes} times; then APPLY. */
		HELD_OTHER,
		/** (T4RD-S, A7a) Refused before it began with a typed {@code SITE_BUSY} ({@link #busy}): nothing done. */
		BUSY
	}

	/** (T4RD-S) The typed busy a BUSY answer refuses with (its message carries Architect's raw text, never to be said). */
	dev.larattalabs.architect.api.Busy busy = new dev.larattalabs.architect.api.Busy(dev.larattalabs.architect.api.Busy.Kind.REMOVAL_IN_PROGRESS, "grp_other",
		"group", "steward_mc:settlement/other");
	/** (T4RD-S) {@code Sites.removalResult(kind, id)}: by {@code kind + ":" + id}. */
	final Map<String, RemoveResult> removalResults = new HashMap<>();

	ArchitectRefused busyRefusal() {
		return new ArchitectRefused(Reason.SITE_BUSY, "Not yet: SENTINEL-RAW is still in progress over [1,2,3]", busy);
	}

	/** (T4S-PRE) The place a {@code held(decided)} names ({@code chunk x,z}, or {@code records unreadable}). */
	String heldWhere = "chunk 3,-4";
	/** (T4S-PRE) How many more calls a HELD_OTHER answer refuses before it applies. */
	int otherTimes = 1;
	/** (T4S-PRE) Site groups ({@code removeGroup}): group id to its sites. */
	final Map<String, List<String>> siteGroups = new LinkedHashMap<>();

	ArchitectRefused heldDecided() {
		return new ArchitectRefused(Reason.SITE_BUSY, "held(decided) tx-7: " + heldWhere, "held(decided)");
	}

	static ArchitectRefused heldOther() {
		return new ArchitectRefused(Reason.SITE_BUSY, "held(other) tx-3: crate 1,64,2 SENTINEL-RAW", "held(other)");
	}

	static final class Rev {
		final int rid;
		int position;
		String state;
		final List<String> sites;
		/** The plan it was realised from ({@code plan_<rid>}, or (FENCE-TESTS-4) a growth's plan). */
		String planId;
		/** (slice DRS) What it waits for ({@code RevisionView.waiting}), when a test gives it. */
		String waiting;
		/** (RVC) Its typed stop cause ({@code RevisionView.removalStopCause}); Architect makes it OTHER when waiting with none, null when not. */
		dev.larattalabs.architect.api.RemovalStopCause removalStopCause;

		Rev(int rid, int position, String state, List<String> sites) {
			this.rid = rid;
			this.position = position;
			this.state = state;
			this.sites = new ArrayList<>(sites);
			this.planId = "plan_" + rid;
		}

		RevisionView view() {
			return new RevisionView(rid, position, rid - 1, planId, "sha", state, 0L, List.of(), List.of(), List.of(), 0L, null, true, waiting, sites,
				removalStopCause);
		}
	}

	static final class Region {
		final String id;
		final String owner;
		RegionState state = RegionState.PLACED;
		int position;
		String busy;
		JsonObject ext = new JsonObject();
		final List<Rev> revisions = new ArrayList<>();
		/** Built from materials (Architect RS): {@code RegionView.build()} non-null. */
		boolean fromMaterials;
		/** (slice SD) Whether all of a region from materials is built ({@code RegionView.build().built()}). */
		boolean built = true;
		/** The {@code architect_mc:land} each position leaves (what {@code undoRevision} to it writes back), when a test gives one. */
		final Map<Integer, String> landAt = new HashMap<>();
		/** Whether a get() of it answers at all (false: Architect has no record, no tombstone). */
		boolean present = true;
		/** (slice DRS) How its last removal stopped ({@code RegionView.removalStop}, Architect DR-S). */
		dev.larattalabs.architect.api.RemovalStop removalStop = dev.larattalabs.architect.api.RemovalStop.NONE;
		/** (slice 8bS) Its typed cause ({@code RegionView.removalStopCause}, S-DRS-1); null with NONE. */
		dev.larattalabs.architect.api.RemovalStopCause removalStopCause;

		Region(String id, String owner) {
			this.id = id;
			this.owner = owner;
		}

		Rev rev(int rid, int position, String state, String... sites) {
			Rev r = new Rev(rid, position, state, List.of(sites));
			revisions.add(r);
			this.position = Math.max(this.position, position);
			return r;
		}

		Region land(int position, String json) {
			landAt.put(position, json);
			if (position == this.position) setLand(json);
			return this;
		}

		void setLand(String json) {
			ext.add("architect_mc:land", JsonParser.parseString(json));
		}

		RegionView view() {
			RegionBuild build = fromMaterials ? new RegionBuild(built, built, built ? 100 : 40, Map.of(), List.of()) : null;
			return new RegionView(id, "plan_" + id, "sha", owner, ext.deepCopy(), "grp_" + id, new BoundingBox(0, -64, 0, 300, 320, 300), state, List.of(), List.of(), 0L,
				Map.of(), null, null, List.of(), state == RegionState.REMOVED ? 1L : 0L, null, List.of(), null, build, position, busy,
				revisions.stream().map(Rev::view).toList(), removalStop, removalStop == dev.larattalabs.architect.api.RemovalStop.NONE ? null : "stopped",
				removalStop == dev.larattalabs.architect.api.RemovalStop.NONE ? null : removalStopCause, null, null);
		}
	}

	static final class Site {
		final String id;
		final String owner;
		State state = State.BUILT;
		String region;
		JsonObject ext = new JsonObject();
		int version = 1;
		long placedAt = 1L;
		BoundingBox box = new BoundingBox(0, 64, 0, 4, 70, 4);

		Site(String id, String owner) {
			this.id = id;
			this.owner = owner;
		}

		Site ext(String key, String value) {
			ext.addProperty(key, value);
			return this;
		}

		SiteView view() {
			return new SiteView(id, "bp_" + id, owner, ext.deepCopy(), box, box, Rotation.NONE, null, state, 0, 0, null, null, null, "building", null, List.of(), List.of(),
				version, version, 0, false, region, null);
		}
	}

	/**
	 * Architect's stockpile ledger for a region built from materials (RS/RV section 8, RU at the pinned 0.67.0): a target draws its bill as it is built and
	 * the draw is recorded per target; an undo refunds what was actually drawn, once per key {@code site:<id>@<placedAt>} (a target never built refunds
	 * nothing). Architect would also undo a target still building (RU); Steward refuses that itself before asking, so the fake never sees it in these tests.
	 */
	static final class Ledger {
		final Map<String, Integer> stock = new HashMap<>();
		final Map<String, Map<String, Integer>> bills = new HashMap<>();
		/** What each target drew, by its refund key. */
		final Map<String, Map<String, Integer>> drawn = new HashMap<>();
		final Set<String> refunded = new HashSet<>();
		int paid, returned;

		void add(String item, int n) {
			stock.merge(item, n, Integer::sum);
		}

		/** A target built from the stockpile: its whole bill drawn (false, nothing drawn, while the stockpile is short). */
		boolean build(FakeArchitect a, String site) {
			var bill = bills.get(site);
			for (var e : bill.entrySet()) if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
			for (var e : bill.entrySet()) { stock.merge(e.getKey(), -e.getValue(), Integer::sum); paid += e.getValue(); }
			Site s = a.sites.get(site);
			s.state = State.BUILT;
			drawn.put(key(s), Map.copyOf(bill));
			return true;
		}

		static String key(Site s) {
			return "site:" + s.id + "@" + s.placedAt;
		}

		/** (slice SD) A target still building draws part of its bill (what it has put down so far), recorded under its key. */
		void drawPart(FakeArchitect a, String site, Map<String, Integer> part) {
			Site s = a.sites.get(site);
			var d = new HashMap<>(drawn.getOrDefault(key(s), Map.of()));
			for (var e : part.entrySet()) {
				stock.merge(e.getKey(), -e.getValue(), Integer::sum);
				paid += e.getValue();
				d.merge(e.getKey(), e.getValue(), Integer::sum);
			}
			drawn.put(key(s), Map.copyOf(d));
		}

		/** An undo's refund of one target: what it drew, once per key. */
		void refund(Site s) {
			String k = key(s);
			var d = drawn.get(k);
			if (d == null || !refunded.add(k)) return;
			for (var b : d.entrySet()) { stock.merge(b.getKey(), b.getValue(), Integer::sum); returned += b.getValue(); }
		}
	}

	final Map<String, Region> regions = new LinkedHashMap<>();
	/** (TESTABLE-ARCH) Approved artifacts' bodies, by sha ({@code regions().artifact}). */
	final Map<String, JsonObject> artifacts = new LinkedHashMap<>();
	/** (TESTABLE-ARCH) What {@code quoteApproved} answers (null: the call is refused). */
	dev.larattalabs.architect.api.Quote quote;
	/** (CRASH-T2) When set, {@code quoteApproved} is never answered until {@link #complete} is run (an answer in flight). */
	boolean quoteHold;
	/** (CRASH-T2) The design groups {@code designs().group} answers, by id; none (empty) when a test gives none. */
	final Map<String, dev.larattalabs.architect.api.Group> groups = new LinkedHashMap<>();
	/** (TESTABLE-ARCH) Every {@code realiseApproved} request, as sent. */
	final List<dev.larattalabs.architect.api.ApprovedRealise> approved = new ArrayList<>();
	/** (TESTABLE-ARCH) How {@code realiseApproved} is answered: APPLY (the region id {@code rg_realised}), REFUSE ({@code BILL_CAP}), or HOLD. */
	Answer realiseAnswer = Answer.HOLD;
	/** (TESTABLE-ARCH) The detail of a {@code BILL_CAP} refusal ({@code lot:<id>}, {@code unknown_lot:<id>}, or "" for one Steward can't read). */
	String capDetail = "lot:hall_1";
	/** (place-words) When set, {@code realiseApproved} fails with it instead (a refusal other than BILL_CAP). */
	RuntimeException realiseFailure;
	/** (FENCE-TESTS-4) Every {@code Regions.realise} request (a growth's, a district's, a plain village's), as sent. */
	final List<dev.larattalabs.architect.api.RealiseRequest> realises = new ArrayList<>();
	/** (FENCE-TESTS-4) The plans {@code planByKey} finds, by their key; none (empty) for a key not given. */
	final Map<String, dev.larattalabs.architect.api.RegionPlan> plansByKey = new LinkedHashMap<>();
	/** (FENCE-TESTS-4) The region a realise from materials puts its lots' sites in, when no {@link #growthOf} is given. */
	static final String GROWN = "rg_grown";
	/**
	 * (FENCE-TESTS-4) The town a growth's realise revises, when a test gives it: the realise adds a revision of the request's plan to it (PLACING, its
	 * sites), found by the realise key and by its plan, and answers the town's id, as Architect's does.
	 */
	String growthOf;
	/**
	 * (CRASH-T) Architect's realise of a town from materials ({@code realiseApproved} with this set): applied when it is sent, as Architect's is (its
	 * answer, per {@link #realiseAnswer}, may still be lost or held: APPLY answers, HOLD leaves it unanswered until {@link #complete}, APPLY_THEN_LOSE
	 * answers a timeout). Each lot of the request takes its put-down bill ({@link #putDown}) from {@link #ledger}; a capped lot ({@code maxBillPerLot})
	 * whose put-down bill is past its cap refuses the whole realise ({@code BILL_CAP}, detail {@code lot:<id>}), nothing written or drawn. Keyed: a
	 * second realise under a key it has answers that region, never a second one, and draws nothing ({@code realiseByKey} finds it too).
	 */
	boolean materials;
	/** (CRASH-T) What each lot takes from the stockpile at put-down (its lot and fill bill), by lot id; a lot not given takes nothing. */
	final Map<String, Map<String, Integer>> putDown = new LinkedHashMap<>();
	/** (CRASH-T) The region each realise key made (Architect's realiseByKey). */
	final Map<String, String> realisedByKey = new LinkedHashMap<>();
	/** (CRASH-T) The owner the materials realise's region is made for (the settlement's). */
	String realiseOwner = "owner";
	/** (TESTABLE-ARCH) Run with each call's line as it is recorded (before it is answered). */
	java.util.function.Consumer<String> onCall = c -> {};
	/**
	 * (FENCE-TESTS-3) Calls that throw synchronously (Architect's API failing on the call itself, not through its future): a call whose line starts
	 * with a key is recorded, then throws that key's error, as an Architect that throws before answering would.
	 */
	final Map<String, Throwable> throwOn = new LinkedHashMap<>();
	/** (FENCE-TESTS-3) The thread each call in {@link #calls} was made on, index for index. */
	final List<Thread> callThreads = new ArrayList<>();
	final Map<String, Site> sites = new LinkedHashMap<>();
	/** Every call, in order; (FENCE-TESTS-3) each one's thread is recorded beside it in {@link #callThreads}, whichever way it is recorded. */
	final List<String> calls = new ArrayList<>() {
		@Override
		public boolean add(String line) {
			callThreads.add(Thread.currentThread());
			return super.add(line);
		}

		@Override
		public void clear() {
			callThreads.clear();
			super.clear();
		}
	};
	final Ledger ledger = new Ledger();
	Answer undoAnswer = Answer.APPLY;
	Answer removeAnswer = Answer.APPLY;
	/** (slice DRS) The blocker a BLOCKED answer gives. */
	String blocker = "a chest of the player's";
	/** (slice DRS) A BLOCKED undoRevision leaves its suffix UNDOING with this wait (Architect DR-S's refused save), when given. */
	String undoWaits;
	/** (RVC) The typed cause a blocked undoRevision leaves on its waiting revisions ({@code RevisionView.removalStopCause}). */
	dev.larattalabs.architect.api.RemovalStopCause undoWaitCause;
	Answer unadoptAnswer = Answer.APPLY;
	/** The calls answered HOLD, to be completed by the test. */
	final List<Runnable> held = new ArrayList<>();
	/** What {@code server.execute} was handed and has not run yet (run by {@link #runServer}); when {@link #inline}, run at once. */
	final List<Runnable> serverQueue = new ArrayList<>();
	boolean inline = true;
	/** (slice BCU) {@code Sites.loadHealthy()}: false models a sites file Architect could not read (it then lists no site). */
	boolean loadHealthy = true;
	/** (slice BCU) {@code ArchitectApi.kitVersion()} and {@code checkerVersion()} (null: no helper connected, empty). */
	String kitVersion = "0.79.0";
	String checkerVersion = "0.79.0";
	/** Architect's features (BC+'s {@code kitVersion} and {@code sitesLoadHealthy} among them). */
	Set<String> features = Set.of("regions", "growDistrict", "regionAdopt", "kitVersion", "sitesLoadHealthy");

	Region region(String id, String owner) {
		Region r = new Region(id, owner);
		regions.put(id, r);
		return r;
	}

	Site site(String id, String owner, String region) {
		Site s = new Site(id, owner);
		s.region = region;
		sites.put(id, s);
		return s;
	}

	long count(String call) {
		return calls.stream().filter(c -> c.startsWith(call)).count();
	}

	void install() {
		ArchitectAccess.useForTest(api(), r -> {
			if (inline) r.run();
			else serverQueue.add(r);
		});
	}

	void runServer() {
		while (!serverQueue.isEmpty()) serverQueue.remove(0).run();
	}

	void complete() {
		var h = List.copyOf(held);
		held.clear();
		h.forEach(Runnable::run);
	}

	private ArchitectApi api() {
		Regions regionsApi = proxy(Regions.class, (m, a) -> switch (m) {
			case "get" -> {
				calls.add("regions.get " + a[0]);
				Region r = regions.get((String) a[0]);
				yield r == null || !r.present ? Optional.empty() : Optional.of(r.view());
			}
			case "list" -> {
				calls.add("regions.list " + a[0]);
				yield regions.values().stream().filter(r -> r.present && (a[0] == null || a[0].equals(r.owner))).map(Region::view).toList();
			}
			case "artifact" -> {
				call("regions.artifact " + a[0]);
				JsonObject body = artifacts.get((String) a[0]);
				yield body == null ? Optional.empty()
					: Optional.of(new dev.larattalabs.architect.api.PlanArtifact((String) a[0], body.deepCopy(), "plan_" + a[0], Set.of(), Set.of(), false, 0L));
			}
			case "checkPalette" -> {
				call("regions.checkPalette " + a[0]);
				yield Optional.empty();
			}
			case "quoteApproved" -> {
				call("regions.quoteApproved " + a[0] + " " + a[1]);
				if (quoteHold) {
					var f = new CompletableFuture<dev.larattalabs.architect.api.Quote>();
					var q = quote;
					held.add(() -> f.complete(q));
					yield f;
				}
				yield quote == null ? CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "no quote")) : CompletableFuture.completedFuture(quote);
			}
			// (FENCE-TESTS-3) a module growth's read-only quote of its plan ({@code Regions.quote}): answered as {@code quoteApproved} is, with {@link #quote}
			case "quote" -> {
				call("regions.quote " + a[0] + " " + a[1]);
				if (quoteHold) {
					var f = new CompletableFuture<dev.larattalabs.architect.api.Quote>();
					var q = quote;
					held.add(() -> f.complete(q));
					yield f;
				}
				yield quote == null ? CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "no quote")) : CompletableFuture.completedFuture(quote);
			}
			// (FENCE-TESTS-3) a growth's plan found by its key: none (the growth plan can't be read back), unless (FENCE-TESTS-4) a test gave it
			case "planByKey" -> {
				call("regions.planByKey " + a[0] + " " + a[1]);
				yield Optional.ofNullable(plansByKey.get((String) a[1]));
			}
			case "realise" -> realise((dev.larattalabs.architect.api.RealiseRequest) a[0]);
			case "realiseApproved" -> realiseApproved((dev.larattalabs.architect.api.ApprovedRealise) a[0]);
			case "realiseByKey" -> {
				call("regions.realiseByKey " + a[1]);
				String id = realisedByKey.get((String) a[1]);
				Region r = id == null ? null : regions.get(id);
				yield r == null || !r.present || !a[0].equals(r.owner) ? Optional.empty() : Optional.of(r.view());
			}
			// (CRASH-T) the artifact's pin under the realise key (close-out A): recorded only
			case "pinArtifact" -> { calls.add("regions.pinArtifact " + a[0]); yield null; }
			case "unpinArtifact" -> { calls.add("regions.unpinArtifact " + a[0]); yield null; }
			case "undoRevision" -> undoRevision((String) a[0], (Integer) a[1], (String) a[2]);
			case "remove" -> removeRegion((String) a[0]);
			case "unadopt" -> unadopt((String) a[0]);
			default -> throw new UnsupportedOperationException("FakeArchitect: Regions." + m + " is not modelled");
		});
		Sites sitesApi = proxy(Sites.class, (m, a) -> switch (m) {
			case "get" -> {
				calls.add("sites.get " + a[0]);
				Site s = loadHealthy ? sites.get((String) a[0]) : null;
				yield s == null ? Optional.empty() : Optional.of(s.view());
			}
			case "list" -> {
				calls.add("sites.list");
				String owner = a == null || a.length == 0 ? null : (String) a[0];
				yield loadHealthy ? sites.values().stream().filter(s -> owner == null || owner.equals(s.owner)).map(Site::view).toList() : List.of();
			}
			case "remove" -> removeSite((String) a[0], (RemoveOptions) a[1]);
			case "removeGroup" -> {
				calls.add("sites.removeGroup " + a[0]);
				String g = (String) a[0];
				yield answer(removeAnswer, () -> { for (String id : siteGroups.getOrDefault(g, List.of())) sites.remove(id); siteGroups.remove(g); });
			}
			case "loadHealthy" -> loadHealthy;
			case "removalResult" -> {
				calls.add("sites.removalResult " + a[0] + " " + a[1]);
				yield removalResults.get(a[0] + ":" + a[1]);
			}
			// (CRASH-T) a composed town is placed as a region, never a batch or a site group: none
			case "batches" -> { calls.add("sites.batches " + a[0]); yield List.of(); }
			case "batch", "group" -> { calls.add("sites." + m + " " + a[0]); yield Optional.empty(); }
			default -> throw new UnsupportedOperationException("FakeArchitect: Sites." + m + " is not modelled");
		});
		dev.larattalabs.architect.api.Designs designsApi = proxy(dev.larattalabs.architect.api.Designs.class, (m, a) -> switch (m) {
			case "group" -> {
				call("designs.group " + a[0]);
				yield Optional.ofNullable(groups.get((String) a[0]));
			}
			// (CRASH-T) a cancelled build's resume stops its design group: answered done (the group is gone)
			case "cancelGroup" -> {
				call("designs.cancelGroup " + a[0]);
				yield CompletableFuture.completedFuture(null);
			}
			default -> throw new UnsupportedOperationException("FakeArchitect: Designs." + m + " is not modelled");
		});
		dev.larattalabs.architect.api.Library libraryApi = proxy(dev.larattalabs.architect.api.Library.class, (m, a) -> switch (m) {
			case "get" -> {
				call("library.get " + a[0]);
				yield Optional.empty();
			}
			default -> throw new UnsupportedOperationException("FakeArchitect: Library." + m + " is not modelled");
		});
		return proxy(ArchitectApi.class, (m, a) -> switch (m) {
			case "regions" -> regionsApi;
			case "designs" -> designsApi;
			case "library" -> libraryApi;
			case "sites" -> sitesApi;
			case "features" -> features;
			case "kitVersion" -> kitVersion;
			case "checkerVersion" -> Optional.ofNullable(checkerVersion);
			default -> throw new UnsupportedOperationException("FakeArchitect: ArchitectApi." + m + " is not modelled");
		});
	}

	/** (TESTABLE-ARCH) Records a call, then runs {@link #onCall} with it. */
	private void call(String line) {
		calls.add(line);
		onCall.accept(line);
		for (var e : throwOn.entrySet()) if (line.startsWith(e.getKey())) sneaky(e.getValue());
	}

	@SuppressWarnings("unchecked")
	private static <T extends Throwable> void sneaky(Throwable t) throws T {
		throw (T) t;
	}

	/**
	 * (TESTABLE-ARCH) Architect's {@code realiseApproved}: recorded as sent; APPLY answers a region id, REFUSE a {@code BILL_CAP} refusal at put-down
	 * (nothing written or taken, as Architect's) with {@link #capDetail}, HOLD answers when {@link #complete} is run (as APPLY).
	 */
	private CompletableFuture<String> realiseApproved(dev.larattalabs.architect.api.ApprovedRealise r) {
		approved.add(r);
		call("regions.realiseApproved " + r.artifactSha() + " " + r.opKey());
		if (realiseFailure != null) return CompletableFuture.failedFuture(realiseFailure);
		if (materials) return realiseFromMaterials(r);
		return switch (realiseAnswer) {
			case APPLY -> CompletableFuture.completedFuture("rg_realised");
			case REFUSE -> CompletableFuture.failedFuture(new ArchitectRefused(Reason.BILL_CAP, "a lot's bill at put-down is past its maxBillPerLot", capDetail));
			case HOLD -> {
				var f = new CompletableFuture<String>();
				held.add(() -> f.complete("rg_realised"));
				yield f;
			}
			default -> throw new UnsupportedOperationException("FakeArchitect: realiseApproved answered " + realiseAnswer + " is not modelled");
		};
	}

	/**
	 * (FENCE-TESTS-4) Architect's {@code Regions.realise} (a growth's revision, a district's or a plain village's region): recorded as sent (then
	 * {@link #throwOn} may throw on the call). From materials ({@code Mode.CONSTRUCTION}), applied as it is sent: past its {@code maxBill} (the whole
	 * put-down bill of its lots, item by item) it is refused ({@code BILL_CAP}), nothing written or drawn; else each lot's site takes its put-down bill
	 * ({@link #putDown}) from {@link #ledger}. {@link #realiseFailure} refuses it instead; its answer is as {@link #realiseAnswer} says. Keyed: asked
	 * again under a key it has, it answers that revision and draws nothing.
	 */
	private CompletableFuture<String> realise(dev.larattalabs.architect.api.RealiseRequest r) {
		realises.add(r);
		call("regions.realise " + r.planId() + " " + r.opKey());
		if (realiseFailure != null) return CompletableFuture.failedFuture(realiseFailure);
		// keyed: asked again under a key it has, the same revision (nothing drawn again)
		if (r.opKey() != null && realisedByKey.containsKey(r.opKey())) return answerRealise(realisedByKey.get(r.opKey()));
		if (r.mode() == dev.larattalabs.architect.api.Mode.CONSTRUCTION) {
			Map<String, Integer> bill = new java.util.TreeMap<>();
			for (String lot : r.lotEntries().keySet()) putDown.getOrDefault(lot, Map.of()).forEach((k, v) -> bill.merge(k, v, Integer::sum));
			if (r.maxBill() != null)
				for (var e : bill.entrySet())
					if (e.getValue() > r.maxBill().getOrDefault(e.getKey(), 0))
						return CompletableFuture.failedFuture(new ArchitectRefused(Reason.BILL_CAP, "the bill at put-down is past its maxBill (" + e.getKey() + ")"));
			String into = growthOf != null ? growthOf : GROWN;
			for (String lot : new java.util.TreeSet<>(r.lotEntries().keySet())) {
				String sid = "s_" + lot;
				site(sid, realiseOwner, into).state = State.BUILDING;
				ledger.bills.put(sid, Map.copyOf(putDown.getOrDefault(lot, Map.of())));
				if (!ledger.build(this, sid)) throw new IllegalStateException("FakeArchitect: the stockpile is short for " + lot + " (give the test more stock)");
			}
		}
		String id = GROWN;
		if (growthOf != null) {
			Region town = regions.get(growthOf);
			int rid = town.revisions.size() + 1;
			town.rev(rid, town.position + 1, "PLACING", r.lotEntries().keySet().stream().sorted().map(l -> "s_" + l).toArray(String[]::new)).planId = r.planId();
			id = growthOf;
		}
		if (r.opKey() != null) realisedByKey.put(r.opKey(), id);
		return answerRealise(id);
	}

	private CompletableFuture<String> answerRealise(String id) {
		return switch (realiseAnswer) {
			case APPLY -> CompletableFuture.completedFuture(id);
			case HOLD -> {
				var f = new CompletableFuture<String>();
				held.add(() -> f.complete(id));
				yield f;
			}
			case APPLY_THEN_LOSE -> CompletableFuture.failedFuture(new TimeoutException("the answer was lost"));
			default -> throw new UnsupportedOperationException("FakeArchitect: a realise answered " + realiseAnswer + " is not modelled");
		};
	}

	/** (CRASH-T) The region a realise from materials makes ({@link #materials}). */
	static final String REALISED = "rg_realised";

	/** (CRASH-T) {@link #materials}: Architect's realise, applied (or refused, nothing drawn) as it is sent; its answer as {@link #realiseAnswer} says. */
	private CompletableFuture<String> realiseFromMaterials(dev.larattalabs.architect.api.ApprovedRealise r) {
		String had = realisedByKey.get(r.opKey());
		if (had == null) {
			var caps = r.maxBillPerLot();
			for (String lot : new java.util.TreeSet<>(r.lotEntries().keySet())) {
				var cap = caps == null ? null : caps.get(lot);
				if (cap == null) continue;
				for (var e : putDown.getOrDefault(lot, Map.of()).entrySet())
					if (e.getValue() > cap.getOrDefault(e.getKey(), 0))
						return CompletableFuture.failedFuture(new ArchitectRefused(Reason.BILL_CAP, "lot " + lot + "'s bill at put-down is past its maxBillPerLot", "lot:" + lot));
			}
			Region region = region(REALISED, realiseOwner);
			region.fromMaterials = true;
			region.built = false;
			region.state = RegionState.PLACING;
			for (String lot : r.lotEntries().keySet()) {
				String sid = "s_" + lot;
				site(sid, realiseOwner, REALISED).state = State.BUILDING;
				ledger.bills.put(sid, Map.copyOf(putDown.getOrDefault(lot, Map.of())));
				if (!ledger.build(this, sid)) throw new IllegalStateException("FakeArchitect: the stockpile is short for " + lot + " (give the test more stock)");
			}
			realisedByKey.put(r.opKey(), REALISED);
			had = REALISED;
		}
		String id = had;
		return switch (realiseAnswer) {
			case APPLY -> CompletableFuture.completedFuture(id);
			case HOLD -> {
				var f = new CompletableFuture<String>();
				held.add(() -> f.complete(id));
				yield f;
			}
			case APPLY_THEN_LOSE -> CompletableFuture.failedFuture(new TimeoutException("the answer was lost"));
			default -> throw new UnsupportedOperationException("FakeArchitect: a materials realise answered " + realiseAnswer + " is not modelled");
		};
	}

	private static boolean standing(Rev v) {
		return !"UNDONE".equals(v.state) && !"STALE".equals(v.state);
	}

	/**
	 * Architect's {@code undoRevision(region, toPosition)}: refused for a region gone, busy (another operation), or a target outside {@code [0, head)}; else
	 * the standing (not UNDONE, not STALE) suffix above {@code toPosition}, newest first: its sites removed (a paid target refunded once), the head moved,
	 * the land put back. While it runs (HOLD) the region is busy and the suffix UNDOING; a same-target call then joins it, as Architect's does.
	 */
	private CompletableFuture<RemoveResult> undoRevision(String id, int toPosition, String actor) {
		calls.add("regions.undoRevision " + id + " " + toPosition);
		Region r = regions.get(id);
		if (r == null || !r.present || r.state == RegionState.REMOVED) return CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "no such region " + id));
		List<Rev> suffix = r.revisions.stream().filter(v -> v.position > toPosition && standing(v)).sorted((x, y) -> Integer.compare(y.position, x.position)).toList();
		boolean joining = r.busy != null && r.busy.equals("undoing to " + toPosition);
		if (joining && joinedHold != null) return joinedHold;
		if (r.busy != null && !r.busy.isBlank()) return CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "busy: " + r.busy));
		boolean resuming = suffix.stream().anyMatch(v -> "UNDOING".equals(v.state));
		if (toPosition < 0 || (toPosition >= r.position && !resuming) || suffix.isEmpty())
			return CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "bad target " + toPosition + " (head " + r.position + ")"));
		Runnable apply = () -> {
			for (Rev v : suffix) {
				v.state = "UNDONE";
				for (String sid : v.sites) {
					Site s = sites.remove(sid);
					if (s != null && r.fromMaterials) ledger.refund(s);
				}
			}
			r.position = toPosition;
			r.busy = null;
			if (r.landAt.containsKey(toPosition)) r.setLand(r.landAt.get(toPosition));
		};
		if (undoAnswer == Answer.HOLD) {
			r.busy = "undoing to " + toPosition;
			suffix.forEach(v -> v.state = "UNDOING");
			var f = new CompletableFuture<RemoveResult>();
			held.add(() -> { apply.run(); joinedHold = null; f.complete(new RemoveResult(true, List.of(), Map.of())); });
			joinedHold = f;
			return f;
		}
		if (undoAnswer == Answer.BLOCKED && undoWaits != null) suffix.forEach(v -> { v.state = "UNDOING"; v.waiting = undoWaits; v.removalStopCause = undoWaitCause; });
		if (undoAnswer == Answer.HELD_DECIDED) suffix.forEach(v -> v.state = "UNDOING");
		return answer(undoAnswer, apply);
	}

	/** The future of an undo in flight (HOLD), which a same-target call joins. */
	private CompletableFuture<RemoveResult> joinedHold;

	private CompletableFuture<RemoveResult> removeRegion(String id) {
		calls.add("regions.remove " + id);
		Region r = regions.get(id);
		if (removeAnswer == Answer.HELD_DECIDED) r.state = RegionState.REMOVING;
		return answer(removeAnswer, () -> {
			r.state = RegionState.REMOVED;
			// (slice SD) a region built from materials: each target's paid blocks back once per key (RS C29: a target still building leaves the feed and
			// refunds what it drew; one never fed refunds nothing)
			if (r.fromMaterials) sites.values().stream().filter(s -> id.equals(s.region)).forEach(ledger::refund);
			sites.values().removeIf(s -> id.equals(s.region));
		});
	}

	private CompletableFuture<UnadoptResult> unadopt(String id) {
		calls.add("regions.unadopt " + id);
		Region r = regions.get(id);
		// Architect's AD: only an adopted region, idle, PLACED, with no standing revision
		if (r == null || !r.present || !r.ext.has(Adoption.KEY_EXT) || r.state != RegionState.PLACED || (r.busy != null && !r.busy.isBlank())
			|| r.revisions.stream().anyMatch(FakeArchitect::standing))
			return CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "not an idle adopted region: " + id));
		Runnable apply = () -> {
			// Architect deletes the record on success (no tombstone); every site goes back to its own group, in no region
			r.present = false;
			sites.values().forEach(s -> { if (id.equals(s.region)) s.region = null; });
		};
		var f = new CompletableFuture<UnadoptResult>();
		switch (unadoptAnswer) {
			case APPLY -> { apply.run(); f.complete(new UnadoptResult(id, Map.of(), List.of())); }
			case APPLY_THEN_LOSE -> { apply.run(); f.completeExceptionally(new TimeoutException("the answer was lost")); }
			case HOLD -> held.add(() -> { apply.run(); f.complete(new UnadoptResult(id, Map.of(), List.of())); });
			default -> f.completeExceptionally(new ArchitectRefused(Reason.SITE_BUSY, "refused"));
		}
		return f;
	}

	/** (T4S-PRE) A site's own answer to {@code Sites.remove}, in place of {@link #removeAnswer}. */
	final Map<String, Answer> siteAnswers = new HashMap<>();

	private CompletableFuture<RemoveResult> removeSite(String id, RemoveOptions o) {
		calls.add("sites.remove " + id);
		return answer(siteAnswers.getOrDefault(id, removeAnswer), () -> sites.remove(id));
	}

	private CompletableFuture<RemoveResult> answer(Answer how, Runnable apply) {
		var f = new CompletableFuture<RemoveResult>();
		if (how == Answer.HELD_OTHER && otherTimes-- <= 0) how = Answer.APPLY;
		switch (how) {
			case HELD_DECIDED -> { held.add(apply); f.completeExceptionally(new java.util.concurrent.CompletionException(heldDecided())); }
			case HELD_OTHER -> f.completeExceptionally(heldOther());
			case BUSY -> f.completeExceptionally(new java.util.concurrent.CompletionException(busyRefusal()));
			case APPLY -> { apply.run(); f.complete(new RemoveResult(true, List.of(), Map.of())); }
			case BLOCKED -> f.complete(new RemoveResult(false, List.of(blocker), Map.of()));
			case REFUSE -> f.completeExceptionally(new ArchitectRefused(Reason.SITE_BUSY, "refused: busy"));
			case APPLY_THEN_LOSE -> { apply.run(); f.completeExceptionally(new TimeoutException("the answer was lost")); }
			case HOLD -> held.add(() -> { apply.run(); f.complete(new RemoveResult(true, List.of(), Map.of())); });
		}
		return f;
	}

	@FunctionalInterface
	private interface Handler {
		Object call(String method, Object[] args) throws Throwable;
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Handler h) {
		InvocationHandler ih = (p, m, a) -> {
			if (m.getDeclaringClass() == Object.class) {
				return switch (m.getName()) {
					case "toString" -> "FakeArchitect " + type.getSimpleName();
					case "hashCode" -> System.identityHashCode(p);
					case "equals" -> p == a[0];
					default -> throw new UnsupportedOperationException(m.getName());
				};
			}
			return h.call(m.getName(), a);
		};
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, ih);
	}
}
