package dev.larattalabs.steward.view;

import dev.larattalabs.labui.ui.TextScale;

/**
 * (UI-PR-S) The text size in Steward's world UI: the steward's nameplate and speech bubble, and the settlement board's slate. lab-ui scales a
 * screen's text while it draws; world text is drawn through the pose, so Steward reads the player's setting itself (lab-ui's
 * {@link TextScale#preference()}, when Steward opted in) and scales the pose: the nameplate and its bubble grow as one, and the board's slate
 * keeps its size in the world while its text grows (fewer rows fit; the rest are counted, as before). Pure.
 */
public final class WorldText {
	/** The board's slate: 3 x 2 blocks at {@code BASE_PPB} pixels a block (MAP-P3B: 48), its row and footer line 11 px. */
	public static final float BASE_PPB = 48;
	public static final int ROW = 11;

	private WorldText() {
	}

	/** The factor world text is drawn at: the player's text size when {@code owner} opted in, else 1. */
	public static float factor(String owner) {
		return factor(TextScale.optedIn(owner), TextScale.preference());
	}

	public static float factor(boolean optedIn, int percent) {
		return optedIn ? TextScale.snap(percent) / 100f : 1f;
	}

	/** The slate's grid at {@code factor}: pixels a block (fewer, so each pixel and its text is larger), and its width and height in those pixels. */
	public record Slate(float ppb, int w, int h) {
		/** The footer's top. */
		public int foot() {
			return h - ROW;
		}

		/** How many building rows fit between {@code y} (under the status lines) and the footer, at least one (the "and N more" line). */
		public int slots(int y) {
			return Math.max(1, (foot() - 2 - y) / ROW);
		}

		/** The status's lines: two at 100 %, one when the text is larger (so a building row still fits). */
		public int statusLines() {
			return ppb < BASE_PPB ? 1 : 2;
		}

		/**
		 * How many of {@code rows} buildings are listed in {@code slots}: all when they fit; else all but the last slot, which counts the rest
		 * ("and N more"), unless there is only one slot: then one building, and the status line says how many there are.
		 */
		public static int shown(int rows, int slots) {
			return rows <= slots ? rows : slots == 1 ? 1 : slots - 1;
		}

		/** Whether the "and N more" line is drawn under the {@code shown} rows. */
		public static boolean more(int rows, int shown, int slots) {
			return rows > shown && slots > 1;
		}
	}

	public static Slate slate(float factor) {
		float ppb = BASE_PPB / (factor <= 0 ? 1f : factor);
		return new Slate(ppb, Math.round(3 * ppb), Math.round(2 * ppb));
	}
}
