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
 * {@code Sites.remove} answers, refusals and lost answers included; and the stockpile ledger of a region built from materials (paid == returned).
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
		HOLD
	}

	static final class Rev {
		final int rid;
		int position;
		String state;
		final List<String> sites;
		final String planId;

		Rev(int rid, int position, String state, List<String> sites) {
			this.rid = rid;
			this.position = position;
			this.state = state;
			this.sites = new ArrayList<>(sites);
			this.planId = "plan_" + rid;
		}

		RevisionView view() {
			return new RevisionView(rid, position, rid - 1, planId, "sha", state, 0L, List.of(), List.of(), List.of(), 0L, null, true, null, sites);
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
		/** The {@code architect_mc:land} each position leaves (what {@code undoRevision} to it writes back), when a test gives one. */
		final Map<Integer, String> landAt = new HashMap<>();
		/** Whether a get() of it answers at all (false: Architect has no record, no tombstone). */
		boolean present = true;

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
			RegionBuild build = fromMaterials ? new RegionBuild(true, true, 100, Map.of(), List.of()) : null;
			return new RegionView(id, "plan_" + id, "sha", owner, ext.deepCopy(), "grp_" + id, new BoundingBox(0, -64, 0, 300, 320, 300), state, List.of(), List.of(), 0L,
				Map.of(), null, null, List.of(), state == RegionState.REMOVED ? 1L : 0L, null, List.of(), null, build, position, busy,
				revisions.stream().map(Rev::view).toList());
		}
	}

	static final class Site {
		final String id;
		final String owner;
		State state = State.BUILT;
		String region;
		JsonObject ext = new JsonObject();
		int version = 1;
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

	/** Architect's stockpile ledger for a region built from materials, as built (RS/RV section 8): a target draws its bill as it is built; an undo of a built
	 * suffix refunds each paid block once per key {@code site:<id>@<placedAt>}; a target not built refuses the undo (survival_building, nothing written). */
	static final class Ledger {
		final Map<String, Integer> stock = new HashMap<>();
		final Map<String, Map<String, Integer>> bills = new HashMap<>();
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
			a.sites.get(site).state = State.BUILT;
			return true;
		}

		void refund(String site, long placedAt) {
			var bill = bills.get(site);
			if (bill == null || !refunded.add("site:" + site + "@" + placedAt)) return;
			for (var b : bill.entrySet()) { stock.merge(b.getKey(), b.getValue(), Integer::sum); returned += b.getValue(); }
		}
	}

	final Map<String, Region> regions = new LinkedHashMap<>();
	final Map<String, Site> sites = new LinkedHashMap<>();
	final List<String> calls = new ArrayList<>();
	final Ledger ledger = new Ledger();
	Answer undoAnswer = Answer.APPLY;
	Answer removeAnswer = Answer.APPLY;
	Answer unadoptAnswer = Answer.APPLY;
	/** The calls answered HOLD, to be completed by the test. */
	final List<Runnable> held = new ArrayList<>();
	/** What {@code server.execute} was handed and has not run yet (run by {@link #runServer}); when {@link #inline}, run at once. */
	final List<Runnable> serverQueue = new ArrayList<>();
	boolean inline = true;

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
			case "undoRevision" -> undoRevision((String) a[0], (Integer) a[1], (String) a[2]);
			case "remove" -> removeRegion((String) a[0]);
			case "unadopt" -> unadopt((String) a[0]);
			default -> throw new UnsupportedOperationException("FakeArchitect: Regions." + m + " is not modelled");
		});
		Sites sitesApi = proxy(Sites.class, (m, a) -> switch (m) {
			case "get" -> {
				calls.add("sites.get " + a[0]);
				Site s = sites.get((String) a[0]);
				yield s == null ? Optional.empty() : Optional.of(s.view());
			}
			case "list" -> {
				calls.add("sites.list");
				String owner = a == null || a.length == 0 ? null : (String) a[0];
				yield sites.values().stream().filter(s -> owner == null || owner.equals(s.owner)).map(Site::view).toList();
			}
			case "remove" -> removeSite((String) a[0], (RemoveOptions) a[1]);
			default -> throw new UnsupportedOperationException("FakeArchitect: Sites." + m + " is not modelled");
		});
		return proxy(ArchitectApi.class, (m, a) -> switch (m) {
			case "regions" -> regionsApi;
			case "sites" -> sitesApi;
			case "features" -> Set.of("regions", "growDistrict", "regionAdopt");
			default -> throw new UnsupportedOperationException("FakeArchitect: ArchitectApi." + m + " is not modelled");
		});
	}

	private CompletableFuture<RemoveResult> undoRevision(String id, int toPosition, String actor) {
		calls.add("regions.undoRevision " + id + " " + toPosition);
		Region r = regions.get(id);
		if (r == null || !r.present || r.state == RegionState.REMOVED) return CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "no such region " + id));
		// Architect's own preflight (RS): a revision of the suffix whose target is not built refuses, nothing written
		if (r.fromMaterials) {
			for (Rev v : r.revisions) {
				if (v.position <= toPosition || "UNDONE".equals(v.state)) continue;
				for (String sid : v.sites) {
					Site s = sites.get(sid);
					if (s != null && s.state != State.BUILT) return CompletableFuture.failedFuture(new ArchitectRefused(Reason.SITE_BUSY, "survival_building: " + sid + " is not built"));
				}
			}
		}
		return answer(undoAnswer, () -> {
			for (Rev v : r.revisions) {
				if (v.position <= toPosition || "UNDONE".equals(v.state)) continue;
				v.state = "UNDONE";
				for (String sid : v.sites) {
					sites.remove(sid);
					if (r.fromMaterials) ledger.refund(sid, 0L);
				}
			}
			r.position = toPosition;
			if (r.landAt.containsKey(toPosition)) r.setLand(r.landAt.get(toPosition));
		});
	}

	private CompletableFuture<RemoveResult> removeRegion(String id) {
		calls.add("regions.remove " + id);
		Region r = regions.get(id);
		return answer(removeAnswer, () -> {
			r.state = RegionState.REMOVED;
			sites.values().removeIf(s -> id.equals(s.region));
		});
	}

	private CompletableFuture<UnadoptResult> unadopt(String id) {
		calls.add("regions.unadopt " + id);
		Region r = regions.get(id);
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

	private CompletableFuture<RemoveResult> removeSite(String id, RemoveOptions o) {
		calls.add("sites.remove " + id);
		return answer(removeAnswer, () -> sites.remove(id));
	}

	private CompletableFuture<RemoveResult> answer(Answer how, Runnable apply) {
		var f = new CompletableFuture<RemoveResult>();
		switch (how) {
			case APPLY -> { apply.run(); f.complete(new RemoveResult(true, List.of(), Map.of())); }
			case BLOCKED -> f.complete(new RemoveResult(false, List.of("a chest of the player's"), Map.of()));
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
