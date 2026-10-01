package com.example.worldstitch.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * セーブデータを跨いだプレイヤー引き継ぎ処理(方式2: 高速ロード切り替え + プレイヤーデータ引き継ぎ)。
 *
 * 仕組み:
 *  1. 別セーブへ転移する瞬間、現在のプレイヤーの状態(インベントリ・体力・満腹度・経験値等、
 *     および PortalTeleporter が仕込んだ PortalCooldown を含む)を、バニラのプレイヤーデータ
 *     保存と全く同じ形式(TagValueOutput → CompoundTag → 圧縮NBT)でスナップショットし、
 *     「Pos」「Motion」「Dimension」だけを転移先の値に書き換える。
 *  2. それをどのセーブフォルダにも属さない一時領域(saves/ の隣にある worldstitch_pending/)に、
 *     「転移先のワールドID」ごとのフォルダへ <プレイヤーUUID>.dat として書き出す。
 *  3. 転移先のセーブがロードされ、統合サーバーが起動した直後(ServerLifecycleEvents.SERVER_STARTED)に、
 *     そのワールドID宛てのファイルがあれば、そのセーブの players/data/ へそのままコピーしてから削除する。
 *     プレイヤーの入室処理は通常通りそのファイルを読むだけなので、
 *     結果として「そのセーブに元々あったプレイヤーデータ」を上書きする形で引き継ぎが完成する。
 */
public final class PendingTransfer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PENDING_DIR_NAME = "worldstitch_pending";

    private PendingTransfer() {
    }

    /**
     * 別セーブへの転移を開始する。現在のプレイヤーの状態をスナップショットして一時領域に保存する。
     *
     * @param targetDimensionId 転移先の次元ID(例: "minecraft:overworld", "minecraft:the_nether")。
     * @return 保存に成功したら true
     */
    public static boolean beginCrossSaveTransfer(ServerPlayer player, String targetWorldId,
                                                  String targetDimensionId, BlockPos targetPos) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }

        if (player.isPassenger()) {
            player.stopRiding();
        }

        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(player.problemPath(), LOGGER)) {

            TagValueOutput output = TagValueOutput.createWithContext(reporter, player.registryAccess());
            // インベントリ・体力・満腹度・経験値・エフェクト・PortalCooldown等、
            // バニラが playerdata に書くのと同じ内容を丸ごと保存する
            player.saveWithoutId(output);

            // 転移先での位置・速度・次元を上書きする。
            output.store("Pos", Vec3.CODEC,
                    new Vec3(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5));
            output.store("Motion", Vec3.CODEC, Vec3.ZERO);
            output.putString("Dimension", targetDimensionId);

            CompoundTag snapshot = output.buildResult();

            Path pendingDir = getPendingDir(server, targetWorldId);
            Files.createDirectories(pendingDir);
            Path stagedFile = pendingDir.resolve(player.getStringUUID() + ".dat");
            NbtIo.writeCompressed(snapshot, stagedFile);
            LOGGER.info("[Worldstitch] Staged cross-save transfer for {} -> world {} dimension {}",
                    player.getGameProfile().name(), targetWorldId, targetDimensionId);
            return true;
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to stage cross-save transfer for {}",
                    player.getGameProfile().name(), e);
            return false;
        }
    }

    /**
     * 新しいセーブが起動した直後に呼ぶ。自分宛て(=今ロードされたワールドID宛て)の
     * 引き継ぎファイルがあれば、そのセーブの players/data/ に反映する。
     * これはプレイヤーがログイン処理でNBTを読み込むより確実に前(サーバー起動直後)に行う必要がある。
     *
     * @return 1件以上のプレイヤーデータを実際に適用したら true
     *         (= 別セーブからの正規のポータル転移でこのセーブが開かれたことを意味する)
     */
    public static boolean applyPendingTransfers(MinecraftServer server) {
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        Path pendingDir = getPendingDir(server, currentWorldId);
        if (!Files.isDirectory(pendingDir)) {
            return false;
        }

        Path playerDataDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        try {
            Files.createDirectories(playerDataDir);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to prepare player data directory", e);
            return false;
        }

        boolean appliedAny = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pendingDir, "*.dat")) {
            for (Path staged : stream) {
                String fileName = staged.getFileName().toString();
                try {
                    // ファイル名がUUID形式であることだけ確認しておく(壊れたファイル対策)
                    UUID.fromString(fileName.substring(0, fileName.length() - ".dat".length()));
                    Path destination = playerDataDir.resolve(fileName);
                    Files.copy(staged, destination, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(staged);
                    appliedAny = true;
                    LOGGER.info("[Worldstitch] Applied cross-save player data transfer: {}", fileName);
                } catch (IllegalArgumentException | IOException e) {
                    LOGGER.warn("[Worldstitch] Failed to apply pending transfer file {}", fileName, e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to read pending transfer directory", e);
        }
        return appliedAny;
    }

    private static Path getPendingDir(MinecraftServer server, String worldId) {
        Path savesRoot = WorldIdentity.getSavesRoot(server);
        return savesRoot.resolveSibling(PENDING_DIR_NAME).resolve(worldId);
    }
}
