package com.example.worldstitch.util;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「このセーブは、別のセーブへ離脱済みかどうか」を、そのセーブ自身の中に記録しておく仕組み。
 *
 * ワールド間ポータルで A→B と渡ると、A側のセーブデータ上ではプレイヤーは
 * もう存在しないことになる(方式2: 高速ロード切り替えなので、A自体は
 * 「ポータルの外に押し出された状態」で普通に保存されている)。
 * この状態で、うっかりタイトル画面からAを直接選んで開いてしまうと、
 * Aの(古い)続きから再開できてしまい、実際の最新状態(B)と食い違ってしまう。
 *
 * これを防ぐため、A自身のセーブフォルダの中に「B へ離脱済み」というマーカーを置いておき、
 * 次にAが直接開かれた時、WorldstitchMod がこれを検知してBへ自動的に案内する。
 *
 * (グローバルな「今どこがアクティブか」という1つの値で管理する方式だと、
 *  無関係な新規ワールドを作成しただけで誤って「離脱済み」と判定されてしまう
 *  事故があったため、必ず「そのセーブ自身」にマーカーを持たせる方式にしている)
 */
public final class WorldResidency {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEPARTED_TO_FILE_NAME = "worldstitch_departed_to.txt";

    private WorldResidency() {
    }

    /** このセーブから別セーブへ離脱したことを記録する。 */
    public static void markDeparted(MinecraftServer server, String targetWorldId) {
        Path file = getMarkerFile(server);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, targetWorldId);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to write departed-to marker", e);
        }
    }

    /** このセーブが離脱済みなら、その離脱先のワールドIDを返す。離脱していなければ null。 */
    public static String getDepartedTo(MinecraftServer server) {
        Path file = getMarkerFile(server);
        try {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file).trim();
                return content.isEmpty() ? null : content;
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to read departed-to marker", e);
        }
        return null;
    }

    /** このセーブへ正規に(ポータル経由で)戻ってきた時に、離脱記録を消す。 */
    public static void clearDeparted(MinecraftServer server) {
        Path file = getMarkerFile(server);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to clear departed-to marker", e);
        }
    }

    private static Path getMarkerFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DEPARTED_TO_FILE_NAME);
    }
}
