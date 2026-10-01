package com.example.worldstitch.util;

import com.example.worldstitch.WorldstitchMod;
import com.example.worldstitch.block.GatePortalBlock;
import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ポータル面を染料で塗り替えた時、相手側(別セーブ)の枠にもそれを伝えるための仕組み。
 *
 * {@link PendingFrameUnlink}(リンク解除の伝播)と全く同じ考え方で、相手側のセーブは
 * 今この瞬間には開けないため、直接書き込むことはできない。そこで
 * 「このワールドID・この次元・この座標の枠が読み込まれたら、この色に塗り替える」という
 * 予約を一時領域(saves/ の隣)に置いておき、対象セーブでそのフレーム(ブロックエンティティ)が
 * 実際にロードされたタイミング({@code ServerBlockEntityEvents.BLOCK_ENTITY_LOAD}) で適用する。
 */
public final class PendingPortalRecolor {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PENDING_RECOLORS_DIR_NAME = "worldstitch_pending_recolors";

    private static final Queue<Runnable> deferredTasks = new ConcurrentLinkedQueue<>();

    private PendingPortalRecolor() {
    }

    /** targetWorldId・targetDimension の targetFramePos(枠の座標)にあるポータルを、次に読み込まれた時 color に塗るよう予約する。 */
    public static void stage(MinecraftServer server, String targetWorldId, ResourceKey<Level> targetDimension,
                              BlockPos targetFramePos, DyeColor color) {
        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        Path dir = getDir(server, targetWorldId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName(targetDimensionId, targetFramePos)), color.getSerializedName());
            LOGGER.info("[Worldstitch] Staged recolor({}) for world {} dimension {} at {}",
                    color, targetWorldId, targetDimensionId, targetFramePos);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to stage recolor", e);
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
                LOGGER.warn("[Worldstitch] Failed to run deferred recolor task", e);
            }
        }
    }

    /** このフレームの座標にちょうど一致する塗り替え予約があれば適用する。 */
    private static void tryApply(ServerLevel level, GateFrameBlockEntity frame) {
        MinecraftServer server = level.getServer();
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        String currentDimensionId = GateFrameBlockEntity.dimensionIdFromKey(level.dimension());
        Path file = getDir(server, currentWorldId).resolve(fileName(currentDimensionId, frame.getBlockPos()));

        if (!Files.isRegularFile(file)) {
            return;
        }

        DyeColor color;
        try {
            String colorName = Files.readString(file).trim();
            color = DyeColor.byName(colorName, DyeColor.WHITE);
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to read/delete pending recolor file {}", file, e);
            return;
        }

        BlockPos portalCell = findAdjacentPortalCell(level, frame.getBlockPos());
        if (portalCell != null) {
            GatePortalBlock.recolorConnectedPortal(level, portalCell, color);
            LOGGER.info("[Worldstitch] Applied recolor({}) at {}", color, frame.getBlockPos());
        }
    }

    private static BlockPos findAdjacentPortalCell(ServerLevel level, BlockPos framePos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = framePos.relative(direction);
            if (level.getBlockState(neighbor).is(WorldstitchMod.GATE_PORTAL_BLOCK)) {
                return neighbor;
            }
        }
        return null;
    }

    private static String fileName(String dimensionId, BlockPos pos) {
        return PendingFrameLink.sanitizeDimensionId(dimensionId)
                + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".txt";
    }

    private static Path getDir(MinecraftServer server, String worldId) {
        Path savesRoot = WorldIdentity.getSavesRoot(server);
        return savesRoot.resolveSibling(PENDING_RECOLORS_DIR_NAME).resolve(worldId);
    }
}
