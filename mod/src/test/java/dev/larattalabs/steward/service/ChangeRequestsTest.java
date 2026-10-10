package dev.larattalabs.steward.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ChangeRequestsTest {
	@Test
	void aTypedRefusalIsDefiniteALostAnswerIsNot() {
		var typed = new dev.larattalabs.architect.api.RegionRefused(dev.larattalabs.architect.api.Reason.OTHER, "no");
		assertTrue(ChangeRequests.definite(new CompletionException(typed)), "a refusal with a reason: give the proposal back");
		assertFalse(ChangeRequests.definite(new CompletionException(new TimeoutException("no ack"))), "a lost answer: it may still be designed");
		assertFalse(ChangeRequests.definite(new IllegalStateException("link down")));
		// a refusal typed WORLD_STOPPED is not one: the helper keeps designing (a stand-in with the same shape as Architect's typed refusals)
		assertFalse(ChangeRequests.definite(new Stopped()));
	}

	/** Shaped like Architect's ArchitectRefused: a reason() method. */
	static final class Stopped extends RuntimeException {
		public String reason() {
			return "WORLD_STOPPED";
		}
	}

	@Test
	void onThisArchitectChangeRequestsWaitFor0b() {
		// built against 0.12.2: versionOf is not there, so changes are noted instead (on 0b's jar this flips by itself)
		assertEquals(hasVersionOf(), ChangeRequests.available());
	}

	private static boolean hasVersionOf() {
		try {
			dev.larattalabs.architect.api.DesignRequest.class.getMethod("versionOf", String.class, String.class);
			return true;
		} catch (NoSuchMethodException e) {
			return false;
		}
	}
}
