package dev.larattalabs.steward.client.screen;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.labui.client.text.TextFieldView;
import dev.larattalabs.labui.client.text.TextKeys;
import dev.larattalabs.labui.client.text.TextModel;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.TextUtil;
import dev.larattalabs.labui.client.ui.UiStyle;
import dev.larattalabs.labui.client.help.KeysOverlay;
import dev.larattalabs.labui.client.help.WhatsThis;
import dev.larattalabs.labui.client.prefs.LabUiPrefs;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * (UI-PR-S) Steward's screens follow lab-ui's text size (100/125/150 %, set on Architect's Status tab or Steward's Settings tab): they draw inside
 * {@code TextScaleHost}, their input runs at the same scale, and their line advances, rows and chips grow with the text ({@link #lh}, {@link #grow}).
 * Each has lab-ui's "what's this" line under its title ({@link #drawTip}) and its "?" overlay (Shift+/ or F1: its keys and terms), from
 * {@link dev.larattalabs.steward.ui.StewardHelp}.
 * <p>
 * Steward's screens on the ported kit: a frame lays its buttons out ({@link #button}) and they are clicked by mouse or by their number key (1-9); at most one
 * text field takes the keyboard while focused (Enter fires the primary button, Shift+Enter a new line, Tab or a click leaves it). Never pauses and never
 * blurs, so massing ghosts and the world stay in view. Client thread.
 */
public abstract class KitScreen extends Screen {
	/** A button laid out this frame. */
	protected record Btn(String label, int number, boolean primary, boolean danger, boolean enabled, int x, int y, int w, int h, Runnable action) {
		boolean hit(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	/** The owner Steward's screens opt in to the text size under ({@code TextScale.optIn} at client init). */
	public static final String OWNER = "steward_mc";

	private final List<Btn> buttons = new ArrayList<>();
	private final List<Btn> lastButtons = new ArrayList<>();
	/** The text field, or null when the screen has none; {@link #fieldFocused} whether it takes the keys. */
	protected @Nullable TextModel field;
	protected boolean fieldFocused;
	protected final TextFieldView fieldView = new TextFieldView();
	private int[] fieldRect = new int[] {-1, -1, 0, 0};
	protected @Nullable String status;
	protected boolean statusError;

	protected KitScreen(String title) {
		super(Component.literal(title));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fillGradient(0, 0, width, height, UiStyle.withAlpha(UiStyle.INK, 0), UiStyle.withAlpha(UiStyle.INK, 70));
	}

	@Override
	public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		buttons.clear();
		dev.larattalabs.labui.client.prefs.TextScaleHost.render(this, OWNER, g, mouseX, mouseY, (gg, mx, my) -> {
			draw(gg, mx, my);
			// the "?" overlay last, over everything (it swallows the keys and clicks while open)
			KeysOverlay o = overlay();
			if (o != null) o.draw(gg, font, width, height);
		});
		lastButtons.clear();
		lastButtons.addAll(buttons);
	}

	// ------------------------------------------------------------------ (UI-PR-S) the text size and the help

	/** A line advance of {@code px} at the screen's text size (10 px at 100 % is 15 px at 150 %). */
	protected static int lh(int px) {
		return TextUtil.lh(px);
	}

	/** The extra height a text run takes at the screen's text size (0 at 100 %, 4 px at 150 %): rows, chips and pills holding one line grow by it. */
	protected static int grow() {
		return Panels.textGrow();
	}

	/** The gap from the header strip's top to the content under it ({@code top + 18} at 100 %). */
	protected static int belowHeader() {
		return Panels.headerH() + 4;
	}

	/** The height of a two-line row (a dot, a title and a muted line under it), {@code extra} px of padding: 22 at 100 % with 2. */
	protected static int twoLineRow(int extra) {
		return lh(10) * 2 + extra;
	}

	/** {@link #chip}'s height at the text size. */
	protected static int chipH() {
		return CHIP_H + grow();
	}

	/** The screen's help (its tip and its "?" overlay), or null for none. */
	protected dev.larattalabs.steward.ui.StewardHelp.@Nullable Screen help() {
		return null;
	}

	private @Nullable WhatsThis tip;
	private @Nullable KeysOverlay overlay;

	/** The screen's "what's this" line (hidden with its ×, remembered in lab-ui's {@code lab_ui.json}), or null. */
	protected @Nullable WhatsThis tip() {
		var h = help();
		if (h == null) return null;
		if (tip == null) tip = new WhatsThis(LabUiPrefs.tips(), h.key(), dev.larattalabs.steward.ui.StewardHelp.tip(h));
		return tip;
	}

	/** The screen's "?" overlay, or null; its rows are read each frame, so a rebound Steward key shows as bound. */
	public @Nullable KeysOverlay overlay() {
		var h = help();
		if (h == null) return null;
		if (overlay == null) overlay = new KeysOverlay(h.title, () -> dev.larattalabs.steward.ui.StewardHelp.rows(h,
			id -> dev.larattalabs.labui.client.ui.KeyLabels.label(dev.larattalabs.steward.client.hud.Keys.byId(id))),
			dev.larattalabs.steward.ui.StewardHelp.terms(h));
		return overlay;
	}

	/** The "what's this" line at (x, y) in a row {@code w} wide; returns the height it took (0 once hidden), so the content goes under it. */
	protected int drawTip(GuiGraphicsExtractor g, int x, int y, int w, int mouseX, int mouseY) {
		WhatsThis t = tip();
		return t == null ? 0 : t.draw(g, font, x, y, w, mouseX, mouseY);
	}

	/** The height {@link #drawTip} takes (for a panel sized to its content). */
	protected int tipHeight() {
		WhatsThis t = tip();
		return t == null ? 0 : t.height();
	}

	/**
	 * The footer's key hints (key, verb pairs) with the "?" overlay's key named before a closing "Esc" pair when the screen has help: "?" , or
	 * "F1" while a text field takes the keys ("?" types there). What doesn't fit drops the help pair first, then draws nothing, as before.
	 */
	protected void footerHints(GuiGraphicsExtractor g, int x, int y, int inner, String... keyThenVerb) {
		List<String> with = new ArrayList<>(List.of(keyThenVerb));
		if (help() != null) {
			int at = with.size() >= 2 && "Esc".equals(with.get(with.size() - 2)) ? with.size() - 2 : with.size();
			with.addAll(at, List.of(field != null && fieldFocused ? "F1" : "?", "help"));
		}
		for (List<String> h : List.of(with, List.of(keyThenVerb))) {
			String[] a = h.toArray(String[]::new);
			if (UiBits.hintsWidth(font, a) <= inner) {
				UiBits.hints(g, font, x, y, false, a);
				return;
			}
		}
	}

	/** Runs an input handler at the screen's text size, as its draw does (measurements in a handler match what was drawn). */
	private boolean scaled(java.util.function.BooleanSupplier body) {
		float was = dev.larattalabs.labui.ui.TextScale.activate(dev.larattalabs.labui.client.prefs.TextScaleHost.result(this, OWNER).factor());
		try {
			return body.getAsBoolean();
		} finally {
			dev.larattalabs.labui.ui.TextScale.activate(was);
		}
	}

	/** Draws the frame; lay buttons out with {@link #button} and the field with {@link #drawField}. */
	protected abstract void draw(GuiGraphicsExtractor g, int mouseX, int mouseY);

	/** Draws a kit button and registers it for this frame; returns its width. */
	/** Whether a button with a number key is drawn this frame (so far). */
	protected boolean numbered() {
		return buttons.stream().anyMatch(b -> b.number() > 0);
	}

	protected int button(GuiGraphicsExtractor g, String label, int number, boolean primary, boolean danger, boolean enabled, int x, int y, int mouseX, int mouseY,
		Runnable action) {
		int w = UiBits.buttonWidth(font, label, number);
		Btn b = new Btn(label, number, primary, danger, enabled, x, y, w, 20, action);
		UiBits.button(g, font, label, number, x, y, w, primary, !enabled ? UiBits.ButtonState.DISABLED : b.hit(mouseX, mouseY) ? UiBits.ButtonState.HOVER
			: UiBits.ButtonState.NORMAL, danger);
		buttons.add(b);
		return w;
	}

	public static final int CHIP_H = 14;

	/** A small chip (the tab sprites, as Architect's set view draws its per-item Approve / Redirect): registers a click target, returns its width. */
	protected int chip(GuiGraphicsExtractor g, String label, int x, int y, boolean on, boolean enabled, int mouseX, int mouseY, Runnable action) {
		int w = font.width(label) + 12;
		int h = chipH();
		dev.larattalabs.labui.client.ui.Panels.sprite(g, on ? dev.larattalabs.labui.client.ui.Kit.TAB_ACTIVE : dev.larattalabs.labui.client.ui.Kit.TAB_INACTIVE, x, y, w,
			h, enabled ? 0xFFFFFFFF : 0x90FFFFFF);
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		if (hover && !on) g.fill(x + 1, y + 1, x + w - 1, y + h - 1, UiStyle.rowHover());
		g.text(font, label, x + 6, y + 3, !enabled ? UiStyle.color("paper.disabled") : UiBits.ink(), false);
		buttons.add(new Btn(label, 0, false, false, enabled, x, y, w, h, action));
		return w;
	}

	/** A click target drawn by someone else (a lab-ui control): registered for this frame's clicks and number key; returns its width. */
	protected int hitOnly(String label, int number, boolean enabled, int x, int y, int w, int h, Runnable action) {
		buttons.add(new Btn(label, number, false, false, enabled, x, y, w, h, action));
		return w;
	}

	protected int chipWidth(String label) {
		return font.width(label) + 12;
	}

	/** Height of {@link #rowButton} and {@link #option}: lab-ui's 18 px Button minimum and Chip height. */
	public static final int ROW_BUTTON_H = dev.larattalabs.labui.client.widget.Chip.H;

	/** A row's own action (Approve, Build it, Update): lab-ui's kit button at its 18 px minimum, numberless; returns its width. */
	protected int rowButton(GuiGraphicsExtractor g, String label, boolean primary, boolean enabled, int x, int y, int mouseX, int mouseY, Runnable action) {
		int w = rowButtonWidth(label);
		Btn b = new Btn(label, 0, primary, false, enabled, x, y, w, ROW_BUTTON_H, action);
		dev.larattalabs.labui.client.widget.Button.paint(g, font, label, 0, x, y, w, ROW_BUTTON_H, primary, false, !enabled, enabled && b.hit(mouseX, mouseY), false);
		buttons.add(b);
		return w;
	}

	/** {@link #rowButton} at width {@code w} (a pair sharing one width). */
	protected int rowButtonW(GuiGraphicsExtractor g, String label, boolean primary, boolean enabled, int x, int y, int w, int mouseX, int mouseY, Runnable action) {
		Btn b = new Btn(label, 0, primary, false, enabled, x, y, w, ROW_BUTTON_H, action);
		dev.larattalabs.labui.client.widget.Button.paint(g, font, label, 0, x, y, w, ROW_BUTTON_H, primary, false, !enabled, enabled && b.hit(mouseX, mouseY), false);
		buttons.add(b);
		return w;
	}

	protected int rowButtonWidth(String label) {
		return dev.larattalabs.labui.client.widget.Button.width(font, label);
	}

	/** One choice of several (a size, a permission, a way): lab-ui's {@code Chip}, 18 px, selected when {@code on}; returns its width. */
	protected int option(GuiGraphicsExtractor g, String label, int x, int y, boolean on, boolean enabled, int mouseX, int mouseY, Runnable action) {
		var c = new dev.larattalabs.labui.client.widget.Chip(label).selected(on);
		c.disabled(!enabled);
		int w = c.draw(g, font, x, y, mouseX, mouseY);
		buttons.add(new Btn(label, 0, false, false, enabled, x, y, w, ROW_BUTTON_H, action));
		return w;
	}

	protected int optionWidth(String label) {
		return new dev.larattalabs.labui.client.widget.Chip(label).width(font);
	}

	/** Text for what is blocked on the player: lab-ui's {@link UiStyle#clayText()} (#9A3F27, at least 4.5:1 on paper, the inset and the selected fill). */
	protected static int attention() {
		return UiStyle.clayText();
	}

	/** The hint for the number keys drawn this frame: "2-3", "1", or null when none is. */
	protected @Nullable String numberHint() {
		int lo = Integer.MAX_VALUE;
		int hi = 0;
		for (Btn b : buttons) {
			if (b.number() > 0 && b.enabled()) {
				lo = Math.min(lo, b.number());
				hi = Math.max(hi, b.number());
			}
		}
		return hi == 0 ? null : lo == hi ? Integer.toString(lo) : lo + "-" + hi;
	}

	protected int buttonWidth(String label, int number) {
		return UiBits.buttonWidth(font, label, number);
	}

	/** Draws the text field at (x, y), {@code w} wide; returns its height. */
	protected int drawField(GuiGraphicsExtractor g, int x, int y, int w, TextFieldView.Style style) {
		if (field == null) return 0;
		int h = fieldView.height(font, field, w, style);
		fieldRect = new int[] {x, y, w, h};
		fieldView.draw(g, font, field, x, y, w, fieldFocused, style);
		return h;
	}

	protected int fieldHeight(int w, TextFieldView.Style style) {
		return field == null ? 0 : fieldView.height(font, field, w, style);
	}

	/** What Enter does in the field (usually the primary action). */
	protected void submitField() {
	}

	protected void say(String s, boolean error) {
		status = s;
		statusError = error;
	}

	/** The overlay takes a key first ("?" yields to a focused text field), then the screen's {@link #onKey}. */
	@Override
	public final boolean keyPressed(KeyEvent e) {
		KeysOverlay o = overlay();
		if (o != null && o.keyPressed(e, field != null && fieldFocused)) return true;
		return scaled(() -> onKey(e));
	}

	/** The screen's keys: the field's while it is focused, Esc, Tab and Enter to the field, the number keys, then {@link #keyOther}. */
	protected boolean onKey(KeyEvent e) {
		if (field != null && fieldFocused) {
			if (e.isEscape() || e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB) {
				fieldFocused = false;
				return true;
			}
			if (TextKeys.isEnter(e) && !e.hasShiftDown()) {
				submitField();
				return true;
			}
			if (TextKeys.isEnter(e)) {
				field.insert("\n");
				return true;
			}
			TextKeys.handle(e, field);
			return true;
		}
		if (e.isEscape()) {
			onClose();
			return true;
		}
		if (field != null && (e.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB || TextKeys.isEnter(e))) {
			fieldFocused = true;
			return true;
		}
		int k = e.key();
		if (k >= com.mojang.blaze3d.platform.InputConstants.KEY_1 && k <= com.mojang.blaze3d.platform.InputConstants.KEY_9) {
			int n = k - com.mojang.blaze3d.platform.InputConstants.KEY_1 + 1;
			for (Btn b : lastButtons) {
				if (b.number() == n && b.enabled()) {
					b.action().run();
					return true;
				}
			}
		}
		return keyOther(e);
	}

	/** Keys the screen handles itself (arrows); false = not handled. */
	protected boolean keyOther(KeyEvent e) {
		return false;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		KeysOverlay o = overlay();
		if (o != null && o.isOpen()) return true;
		if (field != null && fieldFocused && e.codepoint() >= 32) {
			field.insert(e.codepointAsString());
			return true;
		}
		return false;
	}

	/** An open overlay takes any click (and closes); the tip's × hides it; then the screen's {@link #onClick}. */
	@Override
	public final boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		KeysOverlay o = overlay();
		if (o != null && o.mouseClicked(e.x(), e.y())) return true;
		WhatsThis t = tip();
		if (t != null && t.mouseClicked(e.x(), e.y())) return true;
		return scaled(() -> onClick(e));
	}

	/** The buttons, the field, then {@link #clickOther}. */
	protected boolean onClick(MouseButtonEvent e) {
		for (Btn b : lastButtons) {
			if (b.hit(e.x(), e.y())) {
				if (b.enabled()) b.action().run();
				return true;
			}
		}
		if (field != null) {
			boolean inField = e.x() >= fieldRect[0] && e.x() < fieldRect[0] + fieldRect[2] && e.y() >= fieldRect[1] && e.y() < fieldRect[1] + fieldRect[3];
			fieldFocused = inField;
			if (inField) return true;
		}
		return clickOther(e.x(), e.y());
	}

	/** Clicks the screen handles itself (rows); false = not handled. */
	protected boolean clickOther(double x, double y) {
		return false;
	}

	@Override
	protected void init() {
		if (minecraft != null) minecraft.onTextInputFocusChange(this, field != null);
	}

	@Override
	public void removed() {
		if (minecraft != null) minecraft.onTextInputFocusChange(this, false);
		super.removed();
	}

	@Override
	public void onClose() {
		if (minecraft != null) minecraft.gui.setScreen(null);
	}

	/** DevBridge: type into the field. */
	public void typeField(String s) {
		if (field != null) {
			field.set(s);
			fieldFocused = true;
		}
	}

	/** DevBridge: press a button by its number. */
	public boolean press(int number) {
		for (Btn b : lastButtons) {
			if (b.number() == number && b.enabled()) {
				b.action().run();
				return true;
			}
		}
		return false;
	}
}
