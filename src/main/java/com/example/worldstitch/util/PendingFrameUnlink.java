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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * ゲート枠が破壊されてリンクが解除された時、相手側(別セーブ)の枠にもそれを伝えるための仕組み。
 *
 * {@link PendingFrameLink}(戻りリンクの予約)と全く同じ考え方で、相手側のセーブは
 * 今この瞬間には開けないため、直接書き込むことはできない。そこで
 * 「このワールドID・この次元・この座標の枠が読み込まれたら、ポータル面とリンクを消す」
 * という予約を一時領域(saves/ の隣)に置いておき、対象セーブでそのフレーム
 * (ブロックエンティティ)が実際にロードされたタイミング
 * ({@code ServerBlockEntityEvents.BLOCK_ENTITY_LOAD}) で適用する。
 *
 * 適用時には、そのセーブ自身の「離脱済み」マーカー({@link WorldResidency})もあわせてクリアする。
 * これをしないと、リンクが切れているのにそのセーブだけが永久にもう片方のセーブへ
 * 案内され続けてしまい、事実上そのセーブへ二度と(直接は)入れなくなってしまうため。
 */
public final class PendingFrameUnlink {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PENDING_UNLINKS_DIR_NAME = "worldstitch_pending_unlinks";

    private static final Queue<Runnable> deferredTasks = new ConcurrentLinkedQueue<>();

    private PendingFrameUnlink() {
    }

    /** targetWorldId・targetDimension の targetPos(正確な座標)にある枠のリンク解除・ポータル面撤去を予約する。 */
    public static void stage(MinecraftServer server, String targetWorldId, ResourceKey<Level> targetDimension,
                              BlockPos targetPos) {
        // この座標宛てにまだ適用されていない「戻りリンク」予約が残っていたら取り消す。
        // 残したままだと、対象セーブが読み込まれた時に PendingFrameLink が先に適用されて
        // せっかくの解除がすぐ後で上書き…と思いきや逆に、対象がここより後に再リンクされていた
        // 場合は「新しいリンクが張られた直後にこの古い解除予約で消されてしまう」という
        // 不具合(解除→同じリンクを張り直しても相互リンクにならない)の原因になるため。
        PendingFrameLink.cancel(server, targetWorldId, targetDimension, targetPos);

        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        Path dir = getDir(server, targetWorldId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(fileName(targetDimensionId, targetPos)), "unlink");
            LOGGER.info("[Worldstitch] Staged unlink for world {} dimension {} at {}",
                    targetWorldId, targetDimensionId, targetPos);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to stage unlink", e);
        }
    }

    /**
     * targetWorldId・targetDimension の targetPos に対して、まだ適用されていないリンク解除予約が
     * あれば取り消す(そのファイルを削除する)。{@link PendingFrameLink#stageReverseLink} から、
     * 新しいリンクを張る際に古い解除予約を無効化するために呼ばれる。
     */
    static void cancel(MinecraftServer server, String targetWorldId, ResourceKey<Level> targetDimension,
                        BlockPos targetPos) {
        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        Path file = getDir(server, targetWorldId).resolve(fileName(targetDimensionId, targetPos));
        try {
            if (Files.deleteIfExists(file)) {
                LOGGER.info("[Worldstitch] Cancelled stale pending unlink for world {} dimension {} at {} "
                        + "(superseded by a new link)", targetWorldId, targetDimensionId, targetPos);
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to cancel pending unlink", e);
        }
    }

    /**
     * サーバー起動直後に呼ぶ。自分宛て(=今読み込んだワールドID宛て)のリンク解除予約が
     * 1件でもあれば、離脱マーカーを先に(このワールドの案内チェックより前に)クリアしておく。
     *
     * これが無いと: 離脱マーカーのクリアは本来「壊れた枠のチャンクが読み込まれてから」
     * ({@link #tryApply}) 行われるが、そのチャンクが実際に読み込まれるタイミングは
     * プレイヤーのログイン完了(JOIN、離脱マーカーを見て自動案内するタイミング)より
     * 後になることがある。その場合、マーカーがまだ残っているせいで「リンクはとっくに
     * 切れているのに毎回もう片方のセーブへ案内される」を1回余分に繰り返してしまう。
     * ここでチャンクの読み込みを待たずに先んじてマーカーだけ消しておくことで、
     * 最初の1回で正しくこのセーブへ入れるようにする
     * (枠のポータル面自体の後始末は、今まで通り {@link #tryApply} が遅れて行う)。
     */
    public static void clearDepartedMarkerIfAnyPending(MinecraftServer server) {
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        Path dir = getDir(server, currentWorldId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            if (stream.iterator().hasNext()) {
                WorldResidency.clearDeparted(server);
                LOGGER.info("[Worldstitch] Pending unlink(s) found for world {}; cleared departed marker early",
                        currentWorldId);
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to check pending unlink directory", e);
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
                LOGGER.warn("[Worldstitch] Failed to run deferred unlink task", e);
            }
        }
    }

    /** このフレームの座標にちょうど一致するリンク解除予約があれば適用する。 */
    private static void tryApply(ServerLevel level, GateFrameBlockEntity frame) {
        MinecraftServer server = level.getServer();
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        String currentDimensionId = GateFrameBlockEntity.dimensionIdFromKey(level.dimension());
        Path file = getDir(server, currentWorldId).resolve(fileName(currentDimensionId, frame.getBlockPos()));

        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to delete applied unlink file {}", file, e);
        }

        PortalAreaHelper.tearDownGateAt(level, frame.getBlockPos());

        // このセーブが(切れたリンクの相手先へ)離脱済みと記録されていたら、それはもう無効なので消す。
        // これをしないと、リンクが切れているのに永久に相手側へ案内され続けて詰んでしまう。
        WorldResidency.clearDeparted(server);

        LOGGER.info("[Worldstitch] Applied unlink at {} (portal removed, link cleared, departed marker cleared)",
                frame.getBlockPos());
    }

    private static String fileName(String dimensionId, BlockPos pos) {
        return PendingFrameLink.sanitizeDimensionId(dimensionId)
                + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".txt";
    }

    private static Path getDir(MinecraftServer server, String worldId) {
        Path savesRoot = WorldIdentity.getSavesRoot(server);
        return savesRoot.resolveSibling(PENDING_UNLINKS_DIR_NAME).resolve(worldId);
    }
}
