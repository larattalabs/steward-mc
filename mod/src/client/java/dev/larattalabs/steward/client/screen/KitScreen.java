package dev.larattalabs.steward.client.screen;

import dev.larattalabs.steward.client.hud.UiBits;
import dev.larattalabs.steward.client.text.TextFieldView;
import dev.larattalabs.steward.client.text.TextKeys;
import dev.larattalabs.steward.client.text.TextModel;
import dev.larattalabs.steward.client.ui.UiStyle;
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
		dev.larattalabs.steward.client.ui.Panels.sprite(g, on ? dev.larattalabs.steward.client.ui.Kit.TAB_ACTIVE : dev.larattalabs.steward.client.ui.Kit.TAB_INACTIVE, x, y, w,
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
