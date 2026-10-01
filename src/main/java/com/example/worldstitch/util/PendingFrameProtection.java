package com.example.worldstitch.util;

import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ゲートの破壊保護をON/OFFした時、リンク先(相手側)の枠にもそれを伝えるための仕組み。
 *
 * {@link PendingPortalRecolor}(染料での塗り替えの伝播)と全く同じ考え方で、相手側が
 * 別セーブの場合は今この瞬間には開けないため、直接書き込むことはできない。そこで
 * 「このワールドID・この次元・この座標の枠が読み込まれたら、保護をON/OFFする」という
 * 予約を一時領域(saves/ の隣)に置いておき、対象セーブでそのフレーム(ブロックエンティティ)が
 * 実際にロードされたタイミング({@code ServerBlockEntityEvents.BLOCK_ENTITY_LOAD})で適用する。
 *
 * 相手側が同じセーブ内(次元が違うだけ)の場合は、予約を使わずその場で直接
 * {@link PortalAreaHelper#setBreakProtectedForGroup} を呼べばよいので、この仕組みは
 * 別セーブ宛ての場合にのみ使う({@link GateLinkOperations#applyBreakProtectionToGroup} 参照)。
 */
public final class PendingFrameProtection {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PENDING_PROTECTIONS_DIR_NAME = "worldstitch_pending_protections";

    private static final Queue<Runnable> deferredTasks = new ConcurrentLinkedQueue<>();

    private PendingFrameProtection() {
    }

    /** targetWorldId・targetDimension の targetFramePos(枠の座標)にあるゲートを、次に読み込まれた時 protect の状態にするよう予約する。 */
    public static void stage(MinecraftServer server, String targetWorldId, ResourceKey<Level> targetDimension,
                              BlockPos targetFramePos, boolean protect) {
        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        Path dir = getDir(server, targetWorldId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName(targetDimensionId, targetFramePos)), protect ? "protect" : "unprotect");
            LOGGER.info("[Worldstitch] Staged protection({}) for world {} dimension {} at {}",
                    protect, targetWorldId, targetDimensionId, targetFramePos);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to stage protection change", e);
        }
    }

    /**
     * ゲートフレームのブロックエンティティが読み込まれた時に呼ぶ。
     * その場では何もせず、実際の確認・適用はキューに積んで後回しにする
     * ({@link PendingFrameLink#queueApply} と同じ理由)。
     */
    public static void queueApply(ServerLevel level, GateFrameBlockEntity frame) {
        deferredTasks.add(() -> tryApply(level, frame));
    }

    /** ServerTickEvents.END_SERVER_TICK から毎ティック呼ぶ。キューに溜まった分をすべて処理する。 */
    public static void runDeferredTasks(MinecraftServer server) {
        Runnable task;
        while ((task = deferredTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.warn("[Worldstitch] Failed to run deferred protection task", e);
            }
        }
    }

    /** このフレームの座標にちょうど一致する保護切り替え予約があれば適用する。 */
    private static void tryApply(ServerLevel level, GateFrameBlockEntity frame) {
        MinecraftServer server = level.getServer();
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        String currentDimensionId = GateFrameBlockEntity.dimensionIdFromKey(level.dimension());
        Path file = getDir(server, currentWorldId).resolve(fileName(currentDimensionId, frame.getBlockPos()));

        if (!Files.isRegularFile(file)) {
            return;
        }

        boolean protect;
        try {
            protect = Files.readString(file).trim().equals("protect");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to read/delete pending protection file {}", file, e);
            return;
        }

        // 対象の枠自身(まだポータル面が形成されていない可能性もあるので、group版ではなく
        // まず枠の存在だけ確認して setBreakProtectedForGroup に委ねる。未形成ならその枠自身にのみ、
        // 形成済みならつながっている構造全体に反映される)。
        PortalAreaHelper.setBreakProtectedForGroup(level, frame.getBlockPos(), protect);
        LOGGER.info("[Worldstitch] Applied protection({}) at {}", protect, frame.getBlockPos());
    }

    private static String fileName(String dimensionId, BlockPos pos) {
        return PendingFrameLink.sanitizeDimensionId(dimensionId)
                + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".txt";
    }

    private static Path getDir(MinecraftServer server, String worldId) {
        Path savesRoot = WorldIdentity.getSavesRoot(server);
        return savesRoot.resolveSibling(PENDING_PROTECTIONS_DIR_NAME).resolve(worldId);
    }
}
