package com.example.worldstitch.client;

import com.example.worldstitch.network.SetGateDescriptionPayload;
import com.example.worldstitch.network.SubmitGateLinkPayload;
import com.example.worldstitch.network.ToggleFrameBreakProtectionPayload;
import com.example.worldstitch.network.UnlinkGateFramePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * ゲートの目印帳(GateLinkerItem)で枠を右クリックした時に開く画面。
 *
 * - 「この枠の目印をコピー」ボタン: このフレーム自身の目印をクリップボードにコピーする
 * - 貼り付け欄 + 「貼り付け」ボタン(クリップボードから読み込む) + 「リンクする」ボタン:
 *   別セーブで発行した目印を貼り付けてリンクする。既にこのゲートがリンク済み(linked=true)の
 *   場合は、1つのゲートに複数リンクを持たせないため「リンクする」ボタンをグレーアウトする
 *   (先に「リンク解除」してからでないと繋ぎ直せない)。
 * - 接続先の表示(リンク済みの場合のみ): 「接続先: セーブ名 (x, y, z)」を上部に表示する
 * - 説明欄 + 「説明を保存」ボタン: このゲートがどこに繋がっているかなどの自由なメモを設定する
 *   (カーソルをこのゲートのポータルに合わせた時に、接続先の情報と一緒に表示される)
 * - 「リンク解除」ボタン(リンク済みの枠の場合のみ表示): 枠を壊さずにリンクだけを解除する。
 *   相互リンクがうまく繋がらなかった時や、ポータルの繋ぎ先を変えたい時の復旧手段として使う。
 *   このゲートが破壊保護中(breakProtected=true)の場合は、誤操作防止のためグレーアウトして
 *   解除できないようにする(先に破壊保護を外す必要がある)。
 * - 「破壊保護」ボタン: このゲート(実際に形成されているポータル面を介してつながっている
 *   枠ブロック全部)を、プレイヤーが誤って壊せないように保護する/解除するトグルボタン。
 *   コンフィグの frameBreakProtectionEnabled が無効な場合、保護自体は効かない
 *   (詳細は PortalAreaHelper#setBreakProtectedForGroup 参照)。
 *
 * 「リンクする」ボタンを押すと、貼り付けた文字列をそのままサーバーへ送り、
 * サーバー側で /worldstitch link と全く同じ処理(GateLinkOperations)を行う。
 * なお上記のグレーアウトはあくまでUI上の誤操作防止であり、サーバー側
 * (GateLinkOperations#applyLink / #unlinkFrameGroup)でも同じ制約を必ず再チェックしている。
 */
public class GateLinkScreen extends Screen {

    private final BlockPos framePos;
    private final String thisMark;
    private final boolean linked;
    private final String targetSummary;
    private final String currentDescription;

    private EditBox pasteBox;
    private EditBox descriptionBox;

    /** 破壊保護トグルと連動して有効/無効を切り替えるため保持しておく(未リンク時は生成されずnullのまま)。 */
    private Button unlinkButton;

    private int targetSummaryY;

    /** クライアント側で表示上だけ追跡する現在の破壊保護状態(サーバーからの初期値を起点に、ボタンを押すたびに反転させる)。 */
    private boolean breakProtected;

    public GateLinkScreen(BlockPos framePos, String thisMark, boolean linked,
                           String targetSummary, String currentDescription, boolean breakProtected) {
        super(Component.translatable("worldstitch.screen.title"));
        this.framePos = framePos;
        this.thisMark = thisMark;
        this.linked = linked;
        this.targetSummary = targetSummary;
        this.currentDescription = currentDescription;
        this.breakProtected = breakProtected;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        // コンパクト化のため、以前(320)より狭い幅を使う。
        int boxWidth = Math.min(230, this.width - 32);

        int y = this.height / 2 - 82;

        if (this.linked) {
            // 「接続先: ...」は入力欄ではなくただの説明表示なので、ウィジェットではなく
            // extractRenderState() の中でテキストとして描画する(ここでは描画位置だけ確保しておく)。
            this.targetSummaryY = y;
            y += 14;
        }

        // このフレームの目印 + コピー ボタン
        this.addRenderableWidget(Button.builder(Component.translatable("worldstitch.screen.copy_mark"), button -> {
                    Minecraft.getInstance().keyboardHandler.setClipboard(this.thisMark);
                    button.setMessage(Component.translatable("worldstitch.screen.copied"));
                })
                .bounds(centerX - boxWidth / 2, y, boxWidth, 18)
                .build());

        y += 24;

        // 貼り付け欄 + 貼り付けボタン
        int pasteButtonWidth = 56;
        int pasteBoxWidth = boxWidth - pasteButtonWidth - 4;

        this.pasteBox = new EditBox(this.font, centerX - boxWidth / 2, y, pasteBoxWidth, 18,
                Component.translatable("worldstitch.screen.paste_box"));
        this.pasteBox.setMaxLength(256);
        this.pasteBox.setHint(Component.translatable("worldstitch.screen.paste_box.hint"));
        this.addRenderableWidget(this.pasteBox);
        this.setInitialFocus(this.pasteBox);

        this.addRenderableWidget(Button.builder(Component.translatable("worldstitch.screen.paste"), button -> {
                    String clipboard = Minecraft.getInstance().keyboardHandler.getClipboard();
                    this.pasteBox.setValue(clipboard);
                })
                .bounds(centerX - boxWidth / 2 + pasteBoxWidth + 4, y, pasteButtonWidth, 18)
                .build());

        y += 22;

        // リンク実行ボタン。既にリンク済みのゲートは、重複リンクを防ぐためグレーアウトする。
        Button linkButton = Button.builder(Component.translatable("worldstitch.screen.link"), button -> {
                    String pasted = this.pasteBox.getValue();
                    if (!pasted.isBlank()) {
                        ClientPlayNetworking.send(new SubmitGateLinkPayload(this.framePos, pasted));
                        this.onClose();
                    }
                })
                .bounds(centerX - boxWidth / 2, y, boxWidth / 2 - 3, 18)
                .build();
        if (this.linked) {
            linkButton.active = false;
        }
        this.addRenderableWidget(linkButton);

        // 閉じるボタン
        this.addRenderableWidget(Button.builder(Component.translatable("worldstitch.screen.close"), button -> this.onClose())
                .bounds(centerX + 3, y, boxWidth / 2 - 3, 18)
                .build());

        y += 24;

        // 説明欄 + 保存ボタン。このゲートがどこに繋がっているかなどの自由なメモ。
        // カーソルをこのゲートのポータルに合わせた時に、接続先の情報と一緒に表示される。
        int saveButtonWidth = 56;
        int descriptionBoxWidth = boxWidth - saveButtonWidth - 4;

        this.descriptionBox = new EditBox(this.font, centerX - boxWidth / 2, y, descriptionBoxWidth, 18,
                Component.translatable("worldstitch.screen.description_box"));
        this.descriptionBox.setMaxLength(128);
        this.descriptionBox.setHint(Component.translatable("worldstitch.screen.description_box.hint"));
        this.descriptionBox.setValue(this.currentDescription);
        this.addRenderableWidget(this.descriptionBox);

        this.addRenderableWidget(Button.builder(Component.translatable("worldstitch.screen.save_description"), button -> {
                    ClientPlayNetworking.send(new SetGateDescriptionPayload(this.framePos, this.descriptionBox.getValue()));
                    button.setMessage(Component.translatable("worldstitch.screen.saved"));
                })
                .bounds(centerX - boxWidth / 2 + descriptionBoxWidth + 4, y, saveButtonWidth, 18)
                .build());

        y += 22;

        // 破壊保護トグルボタン。押すたびに現在の表示状態を反転させてサーバーへ切り替えを依頼する。
        // 実際に保護が効くかどうか(コンフィグでの機能全体の有効/無効)はサーバー側の判定次第。
        this.addRenderableWidget(Button.builder(this.breakProtectionButtonText(), button -> {
                    ClientPlayNetworking.send(new ToggleFrameBreakProtectionPayload(this.framePos));
                    this.breakProtected = !this.breakProtected;
                    button.setMessage(this.breakProtectionButtonText());
                    // 破壊保護のON/OFFに応じて、下のリンク解除ボタンの有効/無効も連動させる。
                    if (this.unlinkButton != null) {
                        this.unlinkButton.active = !this.breakProtected;
                    }
                })
                .bounds(centerX - boxWidth / 2, y, boxWidth, 18)
                .build());

        // リンク解除ボタン(リンク済みの枠の場合のみ表示)。
        // 相互リンクがうまく繋がらなかった時や、繋ぎ先を変えたい時の復旧手段。
        // 枠ブロック自体は壊さない(サーバー側は GateLinkOperations#unlinkFrame を参照)。
        // 破壊保護中は誤操作防止のためグレーアウトし、先に保護を外すよう促す。
        if (this.linked) {
            y += 22;
            this.unlinkButton = Button.builder(Component.translatable("worldstitch.screen.unlink"), button -> {
                        ClientPlayNetworking.send(new UnlinkGateFramePayload(this.framePos));
                        this.onClose();
                    })
                    .bounds(centerX - boxWidth / 2, y, boxWidth, 18)
                    .build();
            if (this.breakProtected) {
                this.unlinkButton.active = false;
            }
            this.addRenderableWidget(this.unlinkButton);
        }
    }

    private Component breakProtectionButtonText() {
        String key = this.breakProtected
                ? "worldstitch.screen.break_protection.on"
                : "worldstitch.screen.break_protection.off";
        return Component.translatable(key);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        if (this.linked) {
            Component text = Component.translatable("worldstitch.screen.target", this.targetSummary)
                    .withStyle(ChatFormatting.AQUA);
            guiGraphics.centeredText(this.font, text, this.width / 2, this.targetSummaryY, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
