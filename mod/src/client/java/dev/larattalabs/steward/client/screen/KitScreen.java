package dev.larattalabs.steward.client.screen;

import dev.larattalabs.labui.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextKeys;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.labui.client.ui.Kit;
import dev.larattalabs.labui.client.ui.Panels;
import dev.larattalabs.labui.client.ui.UiStyle;
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
		draw(g, mouseX, mouseY);
		lastButtons.clear();
		lastButtons.addAll(buttons);
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
		dev.larattalabs.labui.client.ui.Panels.sprite(g, on ? dev.larattalabs.labui.client.ui.Kit.TAB_ACTIVE : dev.larattalabs.labui.client.ui.Kit.TAB_INACTIVE, x, y, w,
			CHIP_H, enabled ? 0xFFFFFFFF : 0x90FFFFFF);
		boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CHIP_H;
		if (hover && !on) g.fill(x + 1, y + 1, x + w - 1, y + CHIP_H - 1, 0x14000000);
		g.text(font, label, x + 6, y + 3, !enabled ? 0xFFA39B8E : UiBits.ink(), false);
		buttons.add(new Btn(label, 0, false, false, enabled, x, y, w, CHIP_H, action));
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

	/**
	 * Text for what is blocked on the player: lab-ui's {@code semantic.clay_text} (UiStyle.clayText()) (#9A3F27, at least 4.5:1 on paper, the inset and the selected fill)
	 * once the lab-ui release after 0.4.0 carries it, the same colour until then. Clay ({@code UiStyle.CLAY_DARK}) as text was 3.76:1 on the inset.
	 */
	protected static int attention() {
		return UiStyle.color("semantic.clay_text", 0xFF9A3F27);
	}

	/**
	 * The kit header with a right-aligned meta line on the title's own baseline (the meta used to sit 2 px above it). The same as lab-ui's
	 * {@code Panels.header(g, font, title, meta, argb, x, y, w)} in the release after 0.4.0; switch to it then.
	 */
	protected void header(GuiGraphicsExtractor g, String title, String meta, int metaArgb, int x, int y, int w) {
		Kit.Padding p = Kit.padding("header");
		int mw = meta == null || meta.isEmpty() ? 0 : font.width(meta);
		Panels.header(g, font, dev.larattalabs.labui.client.ui.TextUtil.ellipsize(font, title, Math.max(0, w - p.left() - p.right() - (mw > 0 ? mw + 8 : 0))), x, y, w);
		if (mw > 0) g.text(font, meta, x + w - p.right() - mw, y + p.top() + 1, metaArgb, false);
	}

	/** A selected list row: the kit's selected fill with a 2 px clay bar at the left (muted text stays 4.8:1 on it; the old clay wash left it 3.95:1). */
	protected static void selectedRow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, UiStyle.selected());
		g.fill(x, y, x + 2, y + h, UiStyle.selectedEdge());
	}

	/** A hovered list row: the kit's hover fill. */
	protected static void hoverRow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, UiStyle.rowHover());
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

	@Override
	public boolean keyPressed(KeyEvent e) {
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
		if (field != null && fieldFocused && e.codepoint() >= 32) {
			field.insert(e.codepointAsString());
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
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
