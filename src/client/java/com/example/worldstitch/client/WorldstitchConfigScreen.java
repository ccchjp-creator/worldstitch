package com.example.worldstitch.client;

import com.example.worldstitch.config.WorldstitchConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ModMenu(導入されていれば)の一覧からWorldstitchの歯車アイコンを押した時に開く設定画面。
 *
 * Cloth Config 等の外部ライブラリには頼らず、目印帳の画面({@link GateLinkScreen})と
 * 同じ手作りのバニラ {@link Screen} として実装している(ModMenu本体以外に追加の
 * 依存MODが不要)。表示・編集するのは {@link WorldstitchConfig} の全項目そのもの。
 *
 * 各行は「左にラベル・右にON/OFFボタンまたは数値入力欄」の横並びで、ウィンドウが
 * 小さい/GUIスケールが高い等で全項目が入りきらない場合は、右端の▲▼ボタンで
 * 縦スクロールできる(ModMenu本体のMOD一覧オプション画面と同じ考え方)。
 * 「保存して閉じる」「キャンセル」はスクロール領域の外、画面の一番下に固定表示する。
 *
 * ここでの変更は画面上の一時的な値としてのみ保持し、「保存して閉じる」を押した時に
 * 初めて {@link WorldstitchConfig} 本体へ反映してファイルへ書き込む。ESCキーや
 * 「キャンセル」で閉じた場合、あるいはウィンドウを閉じた場合は変更を破棄する。
 */
public class WorldstitchConfigScreen extends Screen {

    private static final int FONT_HEIGHT = 9;
    private static final int WIDGET_HEIGHT = 14;
    private static final int ROW_HEIGHT = WIDGET_HEIGHT + 4;
    private static final int ROW_COUNT = 9; // トグル4 + 数値入力5
    private static final int BUTTON_HEIGHT = 16;
    private static final int FOOTER_HEIGHT = BUTTON_HEIGHT + 10;
    private static final int TITLE_AREA_HEIGHT = 16;
    private static final int ARROW_SIZE = 14;

    // 文字色は先頭2桁が不透明度(アルファ値)。FFを付け忘れると完全に透明になり、
    // 文字が全く表示されなくなる(以前のバージョンで実際に起きた不具合)ので必ず付ける。
    private static final int COLOR_TITLE = 0xFFFFFFFF;
    private static final int COLOR_LABEL = 0xFFA0A0A0;

    private final Screen parent;

    // 保存ボタンを押すまでは、この画面上だけで完結する一時的な値として保持する。
    private boolean allowCrossDimensionTravel;
    private boolean allowCrossSaveTravel;
    private boolean allowEnteringDepartedSave;
    private boolean frameBreakProtectionEnabled;

    private EditBox cooldownBox;
    private EditBox minWidthBox;
    private EditBox minHeightBox;
    private EditBox maxSizeBox;
    private EditBox retryTicksBox;

    private final List<ScrollRow> rows = new ArrayList<>();
    private int viewportTop;
    private int viewportBottom;
    private int scrollOffset;
    private int labelMaxWidth;

    public WorldstitchConfigScreen(Screen parent) {
        super(Component.translatable("worldstitch.config.title"));
        this.parent = parent;

        WorldstitchConfig config = WorldstitchConfig.get();
        this.allowCrossDimensionTravel = config.allowCrossDimensionTravel;
        this.allowCrossSaveTravel = config.allowCrossSaveTravel;
        this.allowEnteringDepartedSave = config.allowEnteringDepartedSave;
        this.frameBreakProtectionEnabled = config.frameBreakProtectionEnabled;
    }

    @Override
    protected void init() {
        this.rows.clear();
        this.scrollOffset = 0;

        int centerX = this.width / 2;
        int arrowColumnWidth = ARROW_SIZE + 4;
        int totalWidth = Math.min(300, this.width - 40);
        int rowsWidth = totalWidth - arrowColumnWidth;
        int leftEdge = centerX - totalWidth / 2;
        int arrowX = leftEdge + rowsWidth + 4;

        // ラベル(左68%)と操作ウィジェット(右、残り)に分ける。日本語ラベルは長いものが
        // 多いため、ラベル側を広めに取り、代わりにボタン・入力欄側は狭くしている。
        int labelWidth = (int) (rowsWidth * 0.68);
        int gap = 4;
        int widgetX = leftEdge + labelWidth + gap;
        int widgetWidth = rowsWidth - labelWidth - gap;
        int labelCenterX = leftEdge + labelWidth / 2;
        this.labelMaxWidth = labelWidth - 4;

        this.viewportTop = TITLE_AREA_HEIGHT + 4;
        this.viewportBottom = this.height - FOOTER_HEIGHT - 4;

        WorldstitchConfig config = WorldstitchConfig.get();

        int y = this.viewportTop;

        y = this.addToggleRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.cross_dimension"),
                this.allowCrossDimensionTravel,
                value -> this.allowCrossDimensionTravel = value);

        y = this.addToggleRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.cross_save"),
                this.allowCrossSaveTravel,
                value -> this.allowCrossSaveTravel = value);

        y = this.addToggleRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.enter_departed"),
                this.allowEnteringDepartedSave,
                value -> this.allowEnteringDepartedSave = value);

        y = this.addToggleRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.break_protection"),
                this.frameBreakProtectionEnabled,
                value -> this.frameBreakProtectionEnabled = value);

        this.cooldownBox = this.addNumberRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.cooldown"), config.portalCooldownTicks);
        y += ROW_HEIGHT;

        this.minWidthBox = this.addNumberRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.min_width"), config.minGateInnerWidth);
        y += ROW_HEIGHT;

        this.minHeightBox = this.addNumberRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.min_height"), config.minGateInnerHeight);
        y += ROW_HEIGHT;

        this.maxSizeBox = this.addNumberRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.max_size"), config.maxGateInnerSize);
        y += ROW_HEIGHT;

        this.retryTicksBox = this.addNumberRow(y, labelCenterX, widgetX, widgetWidth,
                Component.translatable("worldstitch.config.retry_ticks"), config.reverseLinkMaxRetryTicks);

        // スクロール用の▲▼ボタン(全項目がビューポート内に収まる場合は押しても動かないだけで実害はない)。
        this.addRenderableWidget(Button.builder(Component.literal("▲"), button -> this.scrollBy(-ROW_HEIGHT))
                .bounds(arrowX, this.viewportTop, ARROW_SIZE, ARROW_SIZE)
                .build());
        this.addRenderableWidget(Button.builder(Component.literal("▼"), button -> this.scrollBy(ROW_HEIGHT))
                .bounds(arrowX, this.viewportBottom - ARROW_SIZE, ARROW_SIZE, ARROW_SIZE)
                .build());

        // 「保存して閉じる」「キャンセル」はスクロール領域の外、常に画面の一番下に固定する。
        int footerY = this.height - FOOTER_HEIGHT + 5;
        this.addRenderableWidget(Button.builder(Component.translatable("worldstitch.config.save_and_close"),
                        button -> this.saveAndClose())
                .bounds(leftEdge, footerY, totalWidth / 2 - 3, BUTTON_HEIGHT)
                .build());

        this.addRenderableWidget(Button.builder(Component.translatable("worldstitch.config.cancel"),
                        button -> Minecraft.getInstance().setScreenAndShow(this.parent))
                .bounds(leftEdge + totalWidth / 2 + 3, footerY, totalWidth / 2 - 3, BUTTON_HEIGHT)
                .build());

        this.updateScroll();
    }

    /** ラベル+ON/OFFトグルボタンを1行追加し、次の行のy座標(スクロール前基準)を返す。 */
    private int addToggleRow(int y, int labelCenterX, int widgetX, int widgetWidth, Component label,
                              boolean initialValue, Consumer<Boolean> onChange) {
        boolean[] state = {initialValue};
        Button button = Button.builder(toggleButtonText(state[0]), b -> {
                    state[0] = !state[0];
                    onChange.accept(state[0]);
                    b.setMessage(toggleButtonText(state[0]));
                })
                .bounds(widgetX, y, widgetWidth, WIDGET_HEIGHT)
                .build();
        this.addRenderableWidget(button);
        this.rows.add(new ScrollRow(button, label, labelCenterX, y));
        return y + ROW_HEIGHT;
    }

    private static Component toggleButtonText(boolean value) {
        return Component.translatable(value ? "worldstitch.config.toggle.on" : "worldstitch.config.toggle.off");
    }

    /** ラベル+数値入力欄を1行追加する(次の行のy座標は呼び出し側で ROW_HEIGHT を加算して求める)。 */
    private EditBox addNumberRow(int y, int labelCenterX, int widgetX, int widgetWidth, Component label,
                                  int initialValue) {
        EditBox box = new EditBox(this.font, widgetX, y, widgetWidth, WIDGET_HEIGHT, label);
        box.setMaxLength(9);
        // このバージョンのEditBoxには入力文字を制限する機能(setFilter相当)が無いため、
        // 数字以外も入力はできてしまう。ただし保存時に数値として読めない内容は
        // 元の値のまま変更しない(parseOrKeep参照)ので、動作上の実害はない。
        box.setValue(Integer.toString(initialValue));
        this.addRenderableWidget(box);
        this.rows.add(new ScrollRow(box, label, labelCenterX, y));
        return box;
    }

    private void scrollBy(int delta) {
        int totalContentHeight = ROW_HEIGHT * ROW_COUNT;
        int viewportHeight = Math.max(0, this.viewportBottom - this.viewportTop);
        int maxScroll = Math.max(0, totalContentHeight - viewportHeight);
        this.scrollOffset = Math.max(0, Math.min(maxScroll, this.scrollOffset + delta));
        this.updateScroll();
    }

    /** 各行のウィジェットを現在のスクロール量に応じて再配置し、ビューポート外のものは非表示・操作不可にする。 */
    private void updateScroll() {
        for (ScrollRow row : this.rows) {
            int actualY = row.naturalY() - this.scrollOffset;
            boolean visible = actualY >= this.viewportTop - 1 && actualY + WIDGET_HEIGHT <= this.viewportBottom + 1;
            row.widget().setY(actualY);
            row.widget().visible = visible;
            row.widget().active = visible;
        }
    }

    /** 数値入力欄の内容を反映してファイルへ保存し、元の画面へ戻る。数値として読めない欄は元の値のまま変更しない。 */
    private void saveAndClose() {
        WorldstitchConfig config = WorldstitchConfig.get();
        config.allowCrossDimensionTravel = this.allowCrossDimensionTravel;
        config.allowCrossSaveTravel = this.allowCrossSaveTravel;
        config.allowEnteringDepartedSave = this.allowEnteringDepartedSave;
        config.frameBreakProtectionEnabled = this.frameBreakProtectionEnabled;

        config.portalCooldownTicks = parseOrKeep(this.cooldownBox, config.portalCooldownTicks);
        config.minGateInnerWidth = parseOrKeep(this.minWidthBox, config.minGateInnerWidth);
        config.minGateInnerHeight = parseOrKeep(this.minHeightBox, config.minGateInnerHeight);
        config.maxGateInnerSize = parseOrKeep(this.maxSizeBox, config.maxGateInnerSize);
        config.reverseLinkMaxRetryTicks = parseOrKeep(this.retryTicksBox, config.reverseLinkMaxRetryTicks);

        WorldstitchConfig.save();
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    private static int parseOrKeep(EditBox box, int fallback) {
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public void onClose() {
        // ESCキーや戻るボタンで閉じた場合は、変更を保存せずそのまま親画面(ModMenuの一覧)へ戻る。
        Minecraft.getInstance().setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);

        guiGraphics.centeredText(this.font, this.getTitle(), this.width / 2, 4, COLOR_TITLE);

        for (ScrollRow row : this.rows) {
            int actualY = row.naturalY() - this.scrollOffset;
            if (actualY < this.viewportTop - 1 || actualY + WIDGET_HEIGHT > this.viewportBottom + 1) {
                continue; // ビューポート外のラベルは描かない(ウィジェット側も非表示・操作不可にしてある)
            }
            int labelY = actualY + (WIDGET_HEIGHT - FONT_HEIGHT) / 2;
            guiGraphics.centeredText(this.font, this.truncateLabel(row.label()), row.labelCenterX(), labelY, COLOR_LABEL);
        }
    }

    /** ラベル欄の幅に収まらない場合、ボタン側と重ならないよう切り詰める(念のための保険)。 */
    private Component truncateLabel(Component label) {
        String full = label.getString();
        if (this.font.width(full) <= this.labelMaxWidth) {
            return label;
        }
        return Component.literal(this.font.plainSubstrByWidth(full, this.labelMaxWidth));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY != 0) {
            this.scrollBy((int) (-Math.signum(scrollY) * ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ScrollRow(AbstractWidget widget, Component label, int labelCenterX, int naturalY) {
    }
}
