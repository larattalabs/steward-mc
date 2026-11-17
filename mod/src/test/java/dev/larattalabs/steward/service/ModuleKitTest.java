package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.larattalabs.steward.module.ModuleCatalog;
import dev.larattalabs.steward.module.ModuleDev;
import dev.larattalabs.steward.module.ModuleGate;
import dev.larattalabs.steward.module.ModuleGate.Env;
import dev.larattalabs.steward.module.ModuleGate.Why;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * (slice BCU) The module gate's running kit over {@link FakeArchitect}, read as {@link ModuleOffers#env()} reads it ({@code ModuleDev.running} over
 * {@link ArchitectAccess}): {@code kitVersion()} and {@code checkerVersion()} when Architect has feature {@code kitVersion}, else unknown; and the
 * live crop_farm@1 judged on what it reads.
 */
class ModuleKitTest {
	private final FakeArchitect a = new FakeArchitect();
	private static final ModuleCatalog LIVE = ModuleCatalog.live(ModuleKitTest.class.getClassLoader());

	@AfterEach
	void tearDown() {
		ArchitectAccess.reset();
	}

	private ModuleGate.Verdict judged() {
		var r = ModuleDev.running(ArchitectAccess.api());
		return ModuleGate.offer(LIVE, "crop_farm", new Env("26.3", "0.77.0", Set.of(ModuleGate.FEATURE), false, false, r.kit(), r.checker()));
	}

	@Test
	void theRunningKitMatchingThePinsOffers() {
		a.install();
		assertEquals(new ModuleDev.Running("0.71.0", "0.71.0"), ModuleDev.running(ArchitectAccess.api()));
		var v = judged();
		assertTrue(v.ok(), v.message());
	}

	@Test
	void anotherKitAStaleHelperOrNoHelperRefusesInWords() {
		a.kitVersion = "0.72.0";
		a.checkerVersion = "0.72.0";
		a.install();
		var kit = judged();
		assertEquals(Why.KIT, kit.why());
		assertTrue(kit.message().contains("runs kit 0.72.0"), kit.message());

		a.kitVersion = "0.71.0";
		a.checkerVersion = "0.70.0";
		assertEquals(Why.KIT, judged().why(), "a stale helper");

		a.checkerVersion = null;
		assertEquals(ModuleGate.UNKNOWN_KIT, ModuleDev.running(ArchitectAccess.api()).checker(), "no helper connected: checkerVersion() is empty");
		assertEquals(Why.KIT, judged().why());
	}

	@Test
	void anArchitectWithoutTheFeatureIsNeverAskedAndOffersNothing() {
		a.features = Set.of("regions");
		a.kitVersion = null; // would be wrong to read: the call is never made
		a.install();
		assertEquals(new ModuleDev.Running(ModuleGate.UNKNOWN_KIT, ModuleGate.UNKNOWN_KIT), ModuleDev.running(ArchitectAccess.api()));
		var v = judged();
		assertEquals(Why.KIT, v.why());
		assertTrue(v.message().contains("(not said)"), v.message());
	}
}
