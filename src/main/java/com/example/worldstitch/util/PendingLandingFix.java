package com.example.worldstitch.util;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 別セーブへの転移時、着地点を「リンクした枠ブロックの座標そのまま」ではなく
 * 「そのゲートの床の上・開口部の中央」に補正するための仕組み。
 *
 * {@link PendingTransfer} がプレイヤーをスナップショットする時点では、まだ転移先セーブを
 * 開いていないためゲートの実際の構造(内寸)を読み取れず、ひとまず枠ブロックの座標を
 * そのまま Pos に書き込んでおくしかない。そこで、転移先セーブでこのプレイヤーが実際に
 * ログインした直後({@code ServerPlayConnectionEvents.JOIN})に、今度は実際のゲート構造を
 * 読み取って正しい着地点へ座標を上書きする。
 */
public final class PendingLandingFix {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PENDING_DIR_NAME = "worldstitch_pending_landing";

    private PendingLandingFix() {
    }

    /** targetWorldId で playerId がログインした時に、rawFramePos を元に着地点を補正するよう予約する。 */
    public static void stage(MinecraftServer server, String targetWorldId, String playerId, BlockPos rawFramePos) {
        Path dir = getDir(server, targetWorldId);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(playerId + ".txt");
            String content = rawFramePos.getX() + "\n" + rawFramePos.getY() + "\n" + rawFramePos.getZ();
            Files.writeString(file, content);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to stage landing fix", e);
        }
    }

    /**
     * プレイヤーがログインした直後に呼ぶ。このセーブ・このプレイヤー宛ての着地補正予約があれば適用する。
     */
    public static void applyIfPending(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return;
        }
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        Path file = getDir(server, currentWorldId).resolve(player.getStringUUID() + ".txt");
        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(file);
            Files.deleteIfExists(file);
            if (lines.size() < 3) {
                return;
            }
            BlockPos rawFramePos = new BlockPos(
                    Integer.parseInt(lines.get(0)),
                    Integer.parseInt(lines.get(1)),
                    Integer.parseInt(lines.get(2)));

            ServerLevel level = (ServerLevel) player.level();
            BlockPos landing = PortalAreaHelper.computeLandingSpot(level, rawFramePos);
            player.teleportTo(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
            LOGGER.info("[Worldstitch] Applied landing fix for {} -> {}",
                    player.getGameProfile().name(), landing);
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("[Worldstitch] Failed to apply landing fix file {}", file, e);
        }
    }

    private static Path getDir(MinecraftServer server, String worldId) {
        Path savesRoot = WorldIdentity.getSavesRoot(server);
        return savesRoot.resolveSibling(PENDING_DIR_NAME).resolve(worldId);
    }
}
