package com.example.worldstitch.client.util;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * ゲートポータルによる「別セーブデータへのジャンプ」の、クライアント側の実処理。
 *
 * 現在のワールドから切断し、ワールドIDに対応するセーブフォルダを saves/ から探し出して、
 * バニラの「セーブを選んで開く」処理(WorldOpenFlows#openWorld)へそのままつなぐ。
 * プレイヤーのインベントリ等は PendingTransfer が既にNBTファイルとして
 * 引き継ぎ済みなので、ここでは純粋に「どのセーブを、どうやって開き直すか」だけを担当する。
 *
 * A→B→C のように離脱記録(worldstitch_departed_to.txt)が連鎖している場合、
 * 実際にBを起動して読み込んでからCへ…と1ワールドずつ経由すると、
 * 中継するワールドが増えるたびに起動回数が増えてしまう。
 * 離脱記録は各セーブフォルダに置かれた小さなテキストファイルに過ぎないため、
 * サーバーを起動しなくても中身を直接読める。そこで実際に開く前に、
 * この連鎖をファイル読み取りだけで最後まで辿り(resolveFinalWorldId)、
 * 最終的な行き先が判明してから、そこへ1回だけ切り替える。
 */
public final class ClientWorldSwitcher {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DEPARTED_TO_FILE_NAME = "worldstitch_departed_to.txt";
    private static final int MAX_CHAIN_HOPS = 64;

    private ClientWorldSwitcher() {
    }

    /** 統合サーバー(ワールド用スレッド)側から呼ばれる想定。実処理はクライアントスレッドへ委譲する。 */
    public static void requestSwitchToWorldId(String targetWorldId) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> performSwitch(minecraft, targetWorldId));
    }

    private static void performSwitch(Minecraft minecraft, String targetWorldId) {
        Path savesDir = minecraft.gameDirectory.toPath().resolve("saves");
        String finalWorldId = resolveFinalWorldId(savesDir, targetWorldId);
        String levelId = findLevelIdForWorldId(savesDir, finalWorldId);
        if (levelId == null) {
            LOGGER.warn("[Worldstitch] No save found for target world id {}", finalWorldId);
            minecraft.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("worldstitch.message.target_save_not_found"));
            return;
        }

        boolean wasLocal = minecraft.isLocalServer();

        // ネザーポータル通過時と違い、これは「別のセーブを開き直す」操作そのものなので、
        // 通常の「セーブして終了」と同じ手順(レベル切断 → 統合サーバー停止)を踏む。
        if (minecraft.level != null) {
            minecraft.level.disconnect(Component.translatable("worldstitch.switching"));
        }
        if (wasLocal) {
            minecraft.disconnectWithSavingScreen();
        } else {
            minecraft.disconnectWithProgressScreen();
        }

        // 切断が完了した直後、タイトル画面を経由せずそのまま対象セーブのロード画面へつなぐ。
        minecraft.createWorldOpenFlows().openWorld(levelId, () -> minecraft.setScreenAndShow(new TitleScreen()));
    }

    /**
     * startWorldId から始まる離脱記録の連鎖を、実際にセーブを開くことなく最後まで辿る。
     * 各セーブの離脱記録ファイルを直接読むだけなので、中継するワールドの数が
     * いくら増えても実際の起動回数は変わらない(最終的な行き先を1回開くだけで済む)。
     * 循環(A→B→A等)や記録の欠落に備え、最大 {@link #MAX_CHAIN_HOPS} 回で打ち切る。
     */
    private static String resolveFinalWorldId(Path savesDir, String startWorldId) {
        Set<String> visited = new HashSet<>();
        String current = startWorldId;

        for (int hop = 0; hop < MAX_CHAIN_HOPS; hop++) {
            if (!visited.add(current)) {
                LOGGER.warn("[Worldstitch] Departure chain cycle detected at world {}; stopping there", current);
                return current;
            }

            Path levelDir = findLevelDirForWorldId(savesDir, current);
            if (levelDir == null) {
                // このワールドのセーブ自体が見つからない = これ以上辿れないので、ここで確定
                return current;
            }

            String next = readDepartedTo(levelDir);
            if (next == null || next.equals(current)) {
                // これ以上「離脱済み」ではない = ここが実際の最新状態
                return current;
            }

            current = next;
        }

        LOGGER.warn("[Worldstitch] Departure chain exceeded {} hops; stopping at {}", MAX_CHAIN_HOPS, current);
        return current;
    }

    /** levelDir 自身の離脱記録ファイルを読む。無ければ null。 */
    private static String readDepartedTo(Path levelDir) {
        Path marker = levelDir.resolve(DEPARTED_TO_FILE_NAME);
        if (!Files.isRegularFile(marker)) {
            return null;
        }
        try {
            String content = Files.readString(marker).trim();
            return content.isEmpty() ? null : content;
        } catch (IOException ignored) {
            return null;
        }
    }

    /** saves/ 以下の各セーブフォルダにある worldstitch_id.txt を調べ、一致するセーブのフォルダ名(レベルID)を返す。 */
    private static String findLevelIdForWorldId(Path savesDir, String targetWorldId) {
        Path levelDir = findLevelDirForWorldId(savesDir, targetWorldId);
        return levelDir == null ? null : levelDir.getFileName().toString();
    }

    /** saves/ 以下の各セーブフォルダにある worldstitch_id.txt を調べ、一致するセーブのフォルダを返す。 */
    private static Path findLevelDirForWorldId(Path savesDir, String targetWorldId) {
        if (!Files.isDirectory(savesDir)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(savesDir)) {
            for (Path levelDir : stream) {
                if (!Files.isDirectory(levelDir)) {
                    continue;
                }
                Path marker = levelDir.resolve("worldstitch_id.txt");
                if (!Files.isRegularFile(marker)) {
                    continue;
                }
                try {
                    String content = Files.readString(marker).trim();
                    if (targetWorldId.equals(content)) {
                        return levelDir;
                    }
                } catch (IOException ignored) {
                    // 読めないマーカーファイルは無視して次を探す
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to scan saves directory", e);
        }
        return null;
    }
}
