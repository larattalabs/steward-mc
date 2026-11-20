package dev.larattalabs.steward.view;

import dev.larattalabs.labui.ui.GhostPalette;
import java.util.List;

/**
 * (UI-PR-S) The settlement map's and the board's state colours in lab-ui's ghost palette ({@code config/lab_ui.json} {@code ghostPalette}, set
 * on Architect's Status tab): the standard palette keeps the kit's clay, brass and sage; Okabe-Ito takes the colours Architect's ghosts use for
 * the same meanings (blue for a building that stands, as the OK outline; orange for one being built, as obstructed; vermillion for one that
 * waits on the player, as refused), so the map and the ghosts read alike for a colour-blind player. The grey of a planned building, the roads'
 * brown and the walls' stone stay in both. Pure; the client passes the standard colours it draws with ({@code UiStyle}).
 */
public final class MapColors {
	/** A planned or held building (a stone grey). */
	public static final int IDLE = 0xFF857B6E;
	/** A built road (an earth brown). */
	public static final int ROAD = 0xFF8A6A45;
	/** A built wall and the lots on it (a dark stone grey). */
	public static final int WALL = 0xFF4A443D;
	/** Okabe-Ito: a building that stands ({@code done}), one being built ({@code working}), one waiting on the player ({@code waiting}). */
	public static final int OKABE_DONE = 0xFF000000 | GhostPalette.Cat.OK_OUTLINE.okabe;
	public static final int OKABE_WORKING = 0xFF000000 | GhostPalette.Cat.OBSTRUCTED.okabe;
	public static final int OKABE_WAITING = 0xFF000000 | GhostPalette.Cat.REFUSED.okabe;
	/** The state families the map colours ({@code SettlementView.statusWords}), and the marks always drawn beside them. */
	public static final List<String> FAMILIES = List.of("done", "working", "waiting", "idle");

	/** The kit's colours for the standard palette, as the client reads them ({@code UiStyle.SAGE}, {@code BRASS}, {@code CLAY}). */
	public record Standard(int done, int working, int waiting) {
	}

	private MapColors() {
	}

	/** A footprint's fill for its state's family in {@code p} ("error" is drawn as "waiting"; anything else is planned). */
	public static int fill(String family, GhostPalette.Palette p, Standard std) {
		boolean okabe = p == GhostPalette.Palette.OKABE_ITO;
		return switch (family == null ? "" : family) {
			case "waiting", "error" -> okabe ? OKABE_WAITING : std.waiting();
			case "working" -> okabe ? OKABE_WORKING : std.working();
			case "done" -> okabe ? OKABE_DONE : std.done();
			default -> IDLE;
		};
	}

	/** Every colour the map draws a mark in for {@code p}: the four states, then the road and the wall. */
	public static List<Integer> marks(GhostPalette.Palette p, Standard std) {
		return List.of(fill("done", p, std), fill("working", p, std), fill("waiting", p, std), IDLE, ROAD, WALL);
	}
}
