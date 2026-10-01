package com.example.worldstitch.util;

import com.example.worldstitch.block.GateFrameBlock;
import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.config.WorldstitchConfig;
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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * /worldstitch link を実行した際、相手側(転移先)のフレームにも自動で「戻りリンク」を
 * 仕掛けておくための仕組み(双方向リンクの自動開通)。
 *
 * 相手側のセーブは今この瞬間には開けないため、直接書き込むことはできない。
 * そこで「このワールドID・この次元・この座標のフレームが読み込まれたら、このリンクを
 * 設定する」という予約を一時領域(saves/ の隣)に置いておき、対象セーブでそのフレーム
 * (ブロックエンティティ)が実際にロードされたタイミング
 * ({@code ServerBlockEntityEvents.BLOCK_ENTITY_LOAD}) で適用する。
 *
 * 座標は /worldstitch mark で発行された正確なブロック座標をそのまま使うため、
 * 完全一致で照合する(あいまいな距離判定はしない)。オーバーワールドとネザーなど、
 * 別の次元がたまたま同じ座標を持つことは普通にあるため、次元IDもファイル名に
 * 含めて区別する。
 *
 * ただし BLOCK_ENTITY_LOAD 自体は「スポーン地点周辺のチャンクを準備している」最中
 * (サーバーがまだ通常のティックループに入る前)にも発火することがあり、
 * その場でポータル面のブロックを書き込む({@link GateFrameBlock#tryActivate}) と
 * チャンク読み込み処理と競合してサーバーがフリーズしてしまう。
 * そのため実際の適用はいったんキューに積んでおき、サーバーが完全に立ち上がって
 * 通常のティックに入った後({@code ServerTickEvents.END_SERVER_TICK})にまとめて行う。
 *
 * また、この最初の適用タイミングでは「枠は読み込まれているが、枠の内側や周辺の
 * チャンクはまだ読み込み中」ということがあり得る(特に、転移先のちょうどその座標へ
 * 直接テレポートしてきた直後は、枠のチャンク自体は読み込まれても隣接チャンクの
 * 準備がまだ追いついていないことがある)。この状態で {@link PortalAreaHelper#tryFormPortal}
 * を呼ぶと、実際には正しい形の枠でも「隣接セルが読み込まれていない」せいで
 * 誤って形成失敗と判定されてしまうことがある。
 * これが「たまに戻りリンクのポータルが形成されない(=転移先にポータルが無く戻れない)」
 * 不具合の原因だったため、1回失敗しただけでは諦めず、周辺チャンクが揃うまで
 * 数秒間かけて毎ティック再試行するようにしている(回数は {@link WorldstitchConfig} で調整可能)。
 */
public final class PendingFrameLink {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PENDING_LINKS_DIR_NAME = "worldstitch_pending_links";

    private static final Queue<Runnable> deferredTasks = new ConcurrentLinkedQueue<>();

    private PendingFrameLink() {
    }

    /** targetWorldId・targetDimension の targetPos(正確な座標)にあるフレームへ、戻りリンクを予約する。 */
    public static void stageReverseLink(MinecraftServer server, String targetWorldId,
                                         ResourceKey<Level> targetDimension, BlockPos targetPos,
                                         String thisWorldId, String thisWorldName,
                                         ResourceKey<Level> thisDimension, BlockPos thisPos) {
        // この座標宛てにまだ適用されていない「解除」予約が残っていたら取り消す。
        // 残したままだと、対象セーブが読み込まれた時に「新しいリンクが張られた直後に
        // この古い解除予約で消されてしまう」不具合(解除→同じリンクを張り直しても
        // 相互リンクにならない)が起きるため。
        PendingFrameUnlink.cancel(server, targetWorldId, targetDimension, targetPos);

        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        String thisDimensionId = GateFrameBlockEntity.dimensionIdFromKey(thisDimension);
        // 改行が入るとファイルの行解析が崩れるため、念のため潰しておく(通常のセーブ名では発生しない)。
        String safeWorldName = thisWorldName.replace("\n", " ").replace("\r", " ");
        Path dir = getDir(server, targetWorldId);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName(targetDimensionId, targetPos));
            String content = thisWorldId + "\n" + thisDimensionId + "\n"
                    + thisPos.getX() + "\n" + thisPos.getY() + "\n" + thisPos.getZ() + "\n" + safeWorldName;
            Files.writeString(file, content);
            LOGGER.info("[Worldstitch] Staged reverse link for world {} dimension {} at {}",
                    targetWorldId, targetDimensionId, targetPos);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to stage reverse link", e);
        }
    }

    /**
     * targetWorldId・targetDimension の targetPos に対して、まだ適用されていない戻りリンク予約が
     * あれば取り消す(そのファイルを削除する)。{@link PendingFrameUnlink#stage} から、
     * 新しい解除予約を書く際に古い戻りリンク予約を無効化するために呼ばれる。
     */
    static void cancel(MinecraftServer server, String targetWorldId, ResourceKey<Level> targetDimension,
                        BlockPos targetPos) {
        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetDimension);
        Path file = getDir(server, targetWorldId).resolve(fileName(targetDimensionId, targetPos));
        try {
            if (Files.deleteIfExists(file)) {
                LOGGER.info("[Worldstitch] Cancelled stale pending reverse link for world {} dimension {} at {} "
                        + "(superseded by an unlink)", targetWorldId, targetDimensionId, targetPos);
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to cancel pending reverse link", e);
        }
    }

    /**
     * ゲートフレームのブロックエンティティが読み込まれた時に呼ぶ。
     * その場では何もせず、実際の確認・適用はキューに積んで後回しにする。
     */
    public static void queueApply(ServerLevel level, GateFrameBlockEntity frame) {
        deferredTasks.add(() -> tryApply(level, frame, 0));
    }

    /** ServerTickEvents.END_SERVER_TICK から毎ティック呼ぶ。キューに溜まった分をすべて処理する。 */
    public static void runDeferredTasks(MinecraftServer server) {
        Runnable task;
        while ((task = deferredTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                LOGGER.warn("[Worldstitch] Failed to run deferred reverse-link task", e);
            }
        }
    }

    /**
     * このフレームの座標にちょうど一致する戻りリンク予約があれば適用する。
     *
     * ポータル形成に失敗した場合、周辺チャンクの読み込みがまだ追いついていないだけの
     * 可能性があるため、予約ファイルは消さずに次のティックで再試行する。
     * 設定された回数だけ試しても形成できない場合は、本当に枠の形が不正である
     * 可能性が高いためいったん諦めるが、予約ファイル自体は消さずに残しておく
     * (次にこのチャンクが読み込まれた時、あるいはプレイヤーが枠を作り直して
     * 再読み込みされた時に、また最初から再試行できるようにするため)。
     */
    private static void tryApply(ServerLevel level, GateFrameBlockEntity frame, int attempt) {
        MinecraftServer server = level.getServer();
        String currentWorldId = WorldIdentity.getOrCreateId(server);
        String currentDimensionId = GateFrameBlockEntity.dimensionIdFromKey(level.dimension());
        Path file = getDir(server, currentWorldId).resolve(fileName(currentDimensionId, frame.getBlockPos()));

        if (!Files.isRegularFile(file)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 5) {
                Files.deleteIfExists(file);
                return;
            }
            String sourceWorldId = lines.get(0);
            String sourceDimensionId = lines.get(1);
            int x = Integer.parseInt(lines.get(2));
            int y = Integer.parseInt(lines.get(3));
            int z = Integer.parseInt(lines.get(4));
            // ワールド名対応(Beta0.2.6)より前に書かれた予約ファイルには6行目が無いので、
            // その場合はワールドIDで代用する。
            String sourceWorldName = lines.size() >= 6 ? lines.get(5) : sourceWorldId;

            frame.setLink(sourceWorldId, sourceWorldName, sourceDimensionId, x, y, z);
            boolean formed = GateFrameBlock.tryActivate(level, frame.getBlockPos());

            if (formed) {
                Files.deleteIfExists(file);
                LOGGER.info("[Worldstitch] Applied reverse link at {} -> world {} (portal formed after {} attempt(s))",
                        frame.getBlockPos(), sourceWorldId, attempt + 1);
                return;
            }

            int maxAttempts = WorldstitchConfig.get().reverseLinkMaxRetryTicks;
            if (attempt + 1 < maxAttempts) {
                deferredTasks.add(() -> tryApply(level, frame, attempt + 1));
                return;
            }

            LOGGER.warn("[Worldstitch] Reverse link at {} -> world {} still did NOT form a portal after {} "
                            + "attempts (frame shape invalid?). Will retry the next time this frame is loaded.",
                    frame.getBlockPos(), sourceWorldId, attempt + 1);
        } catch (IOException | NumberFormatException e) {
            LOGGER.warn("[Worldstitch] Failed to apply reverse link file {}", file, e);
        }
    }

    private static String fileName(String dimensionId, BlockPos pos) {
        return sanitizeDimensionId(dimensionId) + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ() + ".txt";
    }

    /** 次元IDの ":" はファイル名に使えないため "_" に置き換える。 */
    static String sanitizeDimensionId(String dimensionId) {
        return dimensionId.replace(':', '_');
    }

    private static Path getDir(MinecraftServer server, String worldId) {
        Path savesRoot = WorldIdentity.getSavesRoot(server);
        return savesRoot.resolveSibling(PENDING_LINKS_DIR_NAME).resolve(worldId);
    }
}
