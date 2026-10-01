package com.example.worldstitch.command;

import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.config.WorldstitchConfig;
import com.example.worldstitch.util.GateLinkOperations;
import com.example.worldstitch.util.PortalAreaHelper;
import com.example.worldstitch.util.WorldResidency;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

/**
 * デバッグ・セットアップ用のコマンド群。
 *
 * 別セーブへのリンクは、2つのセーブを同時に開けないため、
 * 「両方の枠を実際に右クリックして繋ぐ」方式が使えない。そこで、
 *   1. 相手側のセーブで、繋ぎたい枠を見ながら /worldstitch mark を実行する
 *      → 「ワールドID:次元ID:x:y:z:ワールド名」という文字列がチャットに表示され、クリックでコピーできる
 *      (この文字列には自分のワールドIDが含まれているため、ワールドIDを別途確認する必要はない)
 *   2. こちら側のセーブに戻り、繋ぎたい枠を見ながら /worldstitch link <コピーした文字列> を実行する
 * という手順でリンクを作る。
 *
 * (同じことをコマンド無しでできる GateLinkerItem + 専用画面も用意してある。
 *  こちらのコマンド群は、それが使えない状況でのデバッグ用に残している)
 */
public final class WorldstitchCommands {

    private WorldstitchCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("worldstitch")
                        .then(Commands.literal("mark")
                                .executes(WorldstitchCommands::executeMark))
                        .then(Commands.literal("link")
                                .then(Commands.argument("mark", StringArgumentType.greedyString())
                                        .executes(WorldstitchCommands::executeLink)))
                        .then(Commands.literal("unlink")
                                .executes(WorldstitchCommands::executeUnlink))
                        .then(Commands.literal("protect")
                                .executes(WorldstitchCommands::executeProtectToggle))
                        .then(Commands.literal("residency")
                                .executes(WorldstitchCommands::executeResidencyStatus)
                                .then(Commands.literal("allow")
                                        .executes(context -> executeResidencySet(context, true)))
                                .then(Commands.literal("block")
                                        .executes(context -> executeResidencySet(context, false)))
                                .then(Commands.literal("clear")
                                        .executes(WorldstitchCommands::executeResidencyClear)))));
    }

    private static int executeMark(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("worldstitch.message.command_player_only"));
            return 0;
        }

        if (!GateLinkOperations.isDimensionAllowed(player.level().dimension())) {
            source.sendFailure(Component.translatable("worldstitch.message.dimension_disabled"));
            return 0;
        }

        GateFrameBlockEntity frame = GateLinkOperations.lookAtFrame(player);
        if (frame == null) {
            source.sendFailure(Component.translatable("worldstitch.message.look_at_frame_mark"));
            return 0;
        }

        String mark = GateLinkOperations.buildMark(player.level(), frame.getBlockPos());

        Component markComponent = Component.literal(mark)
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.CopyToClipboard(mark))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("worldstitch.message.click_to_copy"))));

        Component message = Component.translatable("worldstitch.message.mark_prefix").append(markComponent)
                .append(Component.translatable("worldstitch.message.mark_suffix"));

        source.sendSuccess(() -> message, false);
        return 1;
    }

    private static int executeLink(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("worldstitch.message.command_player_only"));
            return 0;
        }

        if (!GateLinkOperations.isDimensionAllowed(player.level().dimension())) {
            source.sendFailure(Component.translatable("worldstitch.message.dimension_disabled"));
            return 0;
        }

        String markInput = StringArgumentType.getString(context, "mark");
        GateLinkOperations.ParsedMark parsedMark = GateLinkOperations.parseMark(markInput);
        if (parsedMark == null) {
            source.sendFailure(Component.translatable("worldstitch.message.invalid_mark_command"));
            return 0;
        }

        GateFrameBlockEntity frame = GateLinkOperations.lookAtFrame(player);
        if (frame == null) {
            source.sendFailure(Component.translatable("worldstitch.message.look_at_frame_link"));
            return 0;
        }

        GateLinkOperations.LinkResult result = GateLinkOperations.applyLink(player, frame, parsedMark);

        if (result == GateLinkOperations.LinkResult.LINKED) {
            source.sendSuccess(() -> Component.translatable(
                    "worldstitch.message.linked", parsedMark.worldId(), parsedMark.pos().toShortString()), true);
        } else if (result == GateLinkOperations.LinkResult.CROSS_DIMENSION_DISABLED) {
            source.sendFailure(Component.translatable("worldstitch.message.cross_dimension_disabled"));
        } else if (result == GateLinkOperations.LinkResult.ALREADY_LINKED) {
            source.sendFailure(Component.translatable("worldstitch.message.already_linked"));
        } else {
            source.sendSuccess(() -> Component.translatable("worldstitch.message.portal_form_failed_command"), true);
        }
        return 1;
    }

    /**
     * 見ている枠のリンクを解除する(枠ブロック自体は壊さない)。
     * ゲートの目印帳の「リンク解除」ボタンと全く同じ処理(GateLinkOperations#unlinkFrame)を行う。
     * 相互リンクがうまく繋がらなかった時や、繋ぎ先を変えたい時の復旧用コマンド。
     */
    private static int executeUnlink(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("worldstitch.message.command_player_only"));
            return 0;
        }

        if (!GateLinkOperations.isDimensionAllowed(player.level().dimension())) {
            source.sendFailure(Component.translatable("worldstitch.message.dimension_disabled"));
            return 0;
        }

        GateFrameBlockEntity frame = GateLinkOperations.lookAtFrame(player);
        if (frame == null) {
            source.sendFailure(Component.translatable("worldstitch.message.look_at_frame_unlink"));
            return 0;
        }

        // 枠のどのブロックを見ていても解除できるよう、繋がっている構造全体から探す。
        GateLinkOperations.UnlinkResult result =
                GateLinkOperations.unlinkFrameGroup(player.level(), frame.getBlockPos());
        if (result == GateLinkOperations.UnlinkResult.UNLINKED) {
            source.sendSuccess(() -> Component.translatable("worldstitch.message.unlinked"), true);
        } else if (result == GateLinkOperations.UnlinkResult.BREAK_PROTECTED) {
            source.sendFailure(Component.translatable("worldstitch.message.unlink_break_protected"));
        } else {
            source.sendSuccess(() -> Component.translatable("worldstitch.message.not_linked"), true);
        }
        return 1;
    }

    /**
     * 見ている枠(実際に形成されているポータル面を介してつながっている構造全体)の破壊保護を
     * ON/OFF切り替える。ゲートの目印帳の「破壊保護」ボタンと全く同じ処理
     * (PortalAreaHelper#setBreakProtectedForGroup)を行う。
     */
    private static int executeProtectToggle(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.translatable("worldstitch.message.command_player_only"));
            return 0;
        }

        GateFrameBlockEntity frame = GateLinkOperations.lookAtFrame(player);
        if (frame == null) {
            source.sendFailure(Component.translatable("worldstitch.message.look_at_frame_protect"));
            return 0;
        }

        boolean currentlyProtected = PortalAreaHelper.isGroupBreakProtected(player.level(), frame.getBlockPos());
        GateLinkOperations.applyBreakProtectionToGroup(player.level(), frame.getBlockPos(), !currentlyProtected);

        if (!currentlyProtected) {
            source.sendSuccess(() -> Component.translatable("worldstitch.message.break_protection_enabled"), true);
        } else {
            source.sendSuccess(() -> Component.translatable("worldstitch.message.break_protection_disabled"), true);
        }
        return 1;
    }

    /**
     * 「別セーブへ離脱済み」のセーブを直接開いた時、自動で案内するかどうかの現在の設定と、
     * 今このセーブ自身に離脱記録が残っているかどうかを表示する。
     */
    private static int executeResidencyStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        boolean allowed = WorldstitchConfig.get().allowEnteringDepartedSave;
        source.sendSuccess(() -> Component.translatable("worldstitch.message.residency_status",
                Component.translatable(allowed ? "worldstitch.message.residency_status.allow"
                        : "worldstitch.message.residency_status.block")), false);

        String departedTo = source.getServer() != null ? WorldResidency.getDepartedTo(source.getServer()) : null;
        if (departedTo != null) {
            source.sendSuccess(() -> Component.translatable("worldstitch.message.residency_departed", departedTo),
                    false);
        }
        return 1;
    }

    /**
     * allowEnteringDepartedSave の設定を切り替えて保存する。
     * true(allow)にすると、離脱済みのセーブでも自動案内されず直接プレイできるようになる。
     */
    private static int executeResidencySet(CommandContext<CommandSourceStack> context, boolean allow) {
        CommandSourceStack source = context.getSource();
        WorldstitchConfig.get().allowEnteringDepartedSave = allow;
        WorldstitchConfig.save();
        source.sendSuccess(() -> Component.translatable(allow
                ? "worldstitch.message.residency_set_allow"
                : "worldstitch.message.residency_set_block"), true);
        return 1;
    }

    /**
     * 今開いているこのセーブ自身に残っている「離脱済み」記録を消す。
     * allowEnteringDepartedSave が true の時に出る案内メッセージを止めたい場合や、
     * このセーブをこれからの本流として使い続けると決めた場合に使う。
     */
    private static int executeResidencyClear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (source.getServer() == null) {
            source.sendFailure(Component.translatable("worldstitch.message.server_only"));
            return 0;
        }
        WorldResidency.clearDeparted(source.getServer());
        source.sendSuccess(() -> Component.translatable("worldstitch.message.residency_cleared"), true);
        return 1;
    }
}
