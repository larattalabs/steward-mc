package dev.larattalabs.steward.client.entity;

import dev.larattalabs.labui.client.world.SpeechBubble;
import java.util.HashMap;
import java.util.Map;

/** The speech bubble of each settlement's steward (client side): shown when it speaks, ticked here, read by the renderer. Client thread. */
public final class StewardVoices {
	private static final Map<String, SpeechBubble> BUBBLES = new HashMap<>();
	private static int age;

	private StewardVoices() {
	}

	public static void say(String settlementId, String text) {
		BUBBLES.computeIfAbsent(settlementId, k -> new SpeechBubble()).show(text, null, System.currentTimeMillis(), age);
	}

	public static void tick() {
		age++;
		BUBBLES.values().forEach(b -> b.tick(age));
	}

	public static SpeechBubble.Layout layout(String settlementId) {
		SpeechBubble b = BUBBLES.get(settlementId);
		return b == null || !b.visible() ? null : b.layout();
	}

	public static float visibility(String settlementId, float partial) {
		SpeechBubble b = BUBBLES.get(settlementId);
		return b == null ? 0f : b.visibility(age, partial);
	}

	public static void clear() {
		BUBBLES.clear();
	}
}
