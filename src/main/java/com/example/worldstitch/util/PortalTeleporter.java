package com.example.worldstitch.util;

import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.config.WorldstitchConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

/**
 * ゲートポータルによる転移処理の入り口。
 *
 * - リンク先が「今読み込んでいるセーブデータと同じワールドID」の場合:
 *   - さらに次元も同じなら、その場で座標転移させる(ネザーポータルと同様、プレイヤーの
 *     状態はそのまま)。
 *   - 次元が違う場合(オーバーワールド⇔ネザー⇔エンドなど)は、同じ統合サーバーが持つ
 *     対象次元の {@link ServerLevel} へ、バニラのポータルと同じ {@link TeleportTransition}
 *     の仕組みで移動させる(プレイヤーのエンティティ自体は同じインスタンスのまま、
 *     所属する ServerLevel だけが切り替わる。別セーブへの切り替えのような
 *     プレイヤーデータのスナップショット保存/復元は不要)。
 *   - この経路(同一セーブ内)はプレイヤー専用のAPIを使っていないため、
 *     {@link #beginTravel(Entity, String, ResourceKey, BlockPos, BlockPos)} は
 *     Mobや落下中のアイテムなど、プレイヤー以外の {@link Entity} 全般でも同様に動作する。
 *
 * - リンク先が「別のセーブデータ」の場合(方式2: 高速ロード切り替え):
 *   1. まず、元の世界(現在のセーブ)側でプレイヤーをポータルの外側へ押し出す。
 *      これをしないと、切断時に保存される「このセーブでのプレイヤー位置」が
 *      ポータルの中のままになってしまう。
 *   2. PendingTransfer でプレイヤーの状態を転移先座標・次元付きでスナップショットし、
 *      一時領域に保存する。
 *   3. ClientWorldSwitchBridge 経由でクライアントに「別セーブへの切り替え」を依頼する。
 *   4. クライアントが現在のセーブを保存して切断し、対象セーブを開き直す。
 *   5. 対象セーブの起動直後に、保存しておいたプレイヤーデータがそのセーブへ適用される。
 *
 *   この経路は「プレイヤーのセーブデータファイルをまるごと書き換える」という
 *   プレイヤー固有の仕組みに全面的に依存しているため、プレイヤー以外の {@link Entity}
 *   では行わない(触れても素通りするだけで何も起きない)。
 *
 * どちらの場合も、転移の直前に {@code player.setPortalCooldown(...)} を仕込んでおく。
 * これはバニラのネザーポータルと全く同じ仕組みで、エンティティの通常のNBT
 * ("PortalCooldown"タグ)として保存されるため、別セーブへのスナップショットにも
 * 自動的に引き継がれる。つまり転移先に着地した瞬間からしばらくは再発火せず、
 * プレイヤーがポータルの外へ移動する猶予が生まれる(ネザーポータルと同じ体験)。
 *
 * 別セーブへの転移機能は統合サーバー(シングルプレイ)であることが前提。
 * また、次元をまたいだ転移(同一セーブ内・別セーブ間のいずれも)は
 * {@link WorldstitchConfig#allowCrossDimensionTravel} が有効な場合のみ行える。
 */
public final class PortalTeleporter {

    private PortalTeleporter() {
    }

    /**
     * @param safeExitInCurrentWorld 別セーブへの転移の場合に、元の世界側でプレイヤーを
     *                               ポータルの外へ押し出すための退避先座標。同一セーブ内の
     *                               リンクの場合は使用しない。
     */
    public static void beginTravel(Entity entity, String targetWorldId, ResourceKey<Level> targetDimension,
                                    BlockPos targetPos, BlockPos safeExitInCurrentWorld) {
        ResourceKey<Level> currentDimension = entity.level().dimension();

        if (!GateLinkOperations.isDimensionAllowed(currentDimension)
                || !GateLinkOperations.isDimensionAllowed(targetDimension)) {
            if (entity instanceof ServerPlayer player) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.dimension_travel_disabled"));
            }
            return;
        }

        String currentWorldId = WorldIdentity.getCurrentId(entity.level());

        // ネザーポータルと同じ「転移直後のクールダウン」を仕込む。
        // 別セーブへの転移の場合、この値はスナップショットにそのまま乗って引き継がれる。
        entity.setPortalCooldown(WorldstitchConfig.get().portalCooldownTicks);

        if (currentWorldId.equals(targetWorldId)) {
            MinecraftServer server = entity.level().getServer();
            ServerLevel targetLevel = server != null ? server.getLevel(targetDimension) : null;
            if (targetLevel == null) {
                if (entity instanceof ServerPlayer player) {
                    player.sendSystemMessage(Component.translatable("worldstitch.message.target_dimension_not_found"));
                }
                return;
            }

            // targetPos はリンクに使われた枠ブロックそのものの座標(角や辺の途中のこともある)なので、
            // そのまま使わず、ゲート構造から「床の上・開口部中央」を計算し直す。
            BlockPos landing = PortalAreaHelper.computeLandingSpot(targetLevel, targetPos);

            if (targetLevel == entity.level()) {
                // 同一セーブ・同一次元: その場で座標転移させる。
                entity.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
            } else {
                // 同一セーブ・別次元: バニラのポータルと同じ TeleportTransition で
                // 対象次元の ServerLevel へ移動させる(エンティティのインスタンスはそのまま)。
                TeleportTransition transition = new TeleportTransition(
                        targetLevel,
                        new Vec3(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5),
                        Vec3.ZERO,
                        entity.getYRot(),
                        entity.getXRot(),
                        Set.of(),
                        TeleportTransition.DO_NOTHING
                );
                entity.teleport(transition);
            }
            return;
        }

        // ここから先(別セーブへの転移)はプレイヤーのセーブデータファイルを直接扱う
        // 仕組みに全面的に依存しているため、プレイヤー以外は対応しない
        // (Mob等が触れても、同一セーブ内の判定で return 済みでなければ、何もせず素通りする)。
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }

        if (!WorldstitchConfig.get().allowCrossSaveTravel) {
            player.sendSystemMessage(Component.translatable("worldstitch.message.cross_save_travel_disabled"));
            return;
        }

        MinecraftServer server = player.level().getServer();
        if (server == null || !server.isSingleplayer()) {
            player.sendSystemMessage(Component.translatable("worldstitch.message.cross_save_singleplayer_only"));
            return;
        }

        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        boolean staged = PendingTransfer.beginCrossSaveTransfer(player, targetWorldId, targetDimensionId, targetPos);
        if (!staged) {
            player.sendSystemMessage(Component.translatable("worldstitch.message.transfer_prepare_failed"));
            return;
        }

        // 元の世界側の「このプレイヤーの位置」をポータルの外側にしておく。
        // (これをしないと、次にこのセーブへ直接入った時にポータルの中から再開してしまう)
        if (safeExitInCurrentWorld != null) {
            player.teleportTo(
                    safeExitInCurrentWorld.getX() + 0.5,
                    safeExitInCurrentWorld.getY(),
                    safeExitInCurrentWorld.getZ() + 0.5
            );
        }

        // このセーブ自身に「targetWorldId へ離脱した」ことを記録しておく。
        // 次に直接このセーブが開かれた時、WorldstitchMod が検知して自動的に案内する。
        WorldResidency.markDeparted(server, targetWorldId);

        // targetPos はリンクに使われた枠ブロックそのものの座標であり、床の上・中央とは限らない。
        // 転移先セーブは今は開けないので正しい着地点をまだ計算できない。ひとまずスナップショットの
        // Pos には targetPos をそのまま入れておき(PendingTransfer)、転移先でこのプレイヤーが
        // 実際にログインした直後にゲート構造を読み取って座標を上書きする。
        PendingLandingFix.stage(server, targetWorldId, player.getStringUUID(), targetPos);

        player.sendSystemMessage(Component.translatable("worldstitch.message.switching_save"));
        ClientWorldSwitchBridge.requestSwitch(targetWorldId);
    }
}
