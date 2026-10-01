package com.example.worldstitch.item;

import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.network.OpenGateLinkScreenPayload;
import com.example.worldstitch.util.GateLinkOperations;
import com.example.worldstitch.util.PortalAreaHelper;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ゲートの目印帳(リンカー)。
 *
 * ゲートフレームを右クリックすると、コマンドを使わずに専用画面(GateLinkScreen)が開き、
 * その場で「この枠の目印をコピー」「別セーブの目印を貼り付けてリンク」ができる。
 * /worldstitch mark, /worldstitch link と全く同じ処理を、GUIで行えるようにしたもの。
 *
 * 設定で次元をまたいだワープが無効になっている場合、オーバーワールド以外
 * (ネザー・エンド等)ではこの画面自体を開かせない(コマンド側も同様の制限を持つ)。
 */
public class GateLinkerItem extends Item {

    public GateLinkerItem(Properties settings) {
        super(settings);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        BlockPos clickedPos = context.getClickedPos();
        BlockEntity clickedEntity = level.getBlockEntity(clickedPos);
        if (!(clickedEntity instanceof GateFrameBlockEntity frame)) {
            return InteractionResult.PASS;
        }

        if (!GateLinkOperations.isDimensionAllowed(level.dimension())) {
            player.sendSystemMessage(Component.translatable("worldstitch.message.dimension_disabled"));
            return InteractionResult.SUCCESS;
        }

        // 枠のどのブロックを右クリックしても同じ情報が出るように、クリックしたブロック単体ではなく、
        // 繋がっている構造全体から実際にリンク情報を持つブロックを探す。
        GateFrameBlockEntity linkedFrame = PortalAreaHelper.findLinkedFrameInGroup(level, frame.getBlockPos());
        boolean linked = linkedFrame != null;
        String targetSummary = "";
        String currentDescription;
        if (linked) {
            BlockPos targetPos = linkedFrame.getTargetPos();
            targetSummary = linkedFrame.getTargetWorldName() + " (" + targetPos.getX() + ", "
                    + targetPos.getY() + ", " + targetPos.getZ() + ")";
            currentDescription = linkedFrame.getLinkDescription();
        } else {
            // 未リンクの場合は、説明文はクリックしたブロック自身のものを使う
            // (壊れた・繋ぎ直し待ちのゲートにもメモを残しておけるように)。
            currentDescription = frame.getLinkDescription();
        }

        boolean breakProtected = PortalAreaHelper.isGroupBreakProtected(level, frame.getBlockPos());

        String mark = GateLinkOperations.buildMark(level, frame.getBlockPos());
        ServerPlayNetworking.send(player, new OpenGateLinkScreenPayload(
                frame.getBlockPos(), mark, linked, targetSummary, currentDescription, breakProtected));

        return InteractionResult.SUCCESS;
    }
}
