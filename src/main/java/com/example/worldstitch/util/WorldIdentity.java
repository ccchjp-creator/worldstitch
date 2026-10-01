package com.example.worldstitch.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 現在ロードされているセーブデータに、恒久的なワールドIDを割り当てる。
 *
 * フォルダ名が変わっても・セーブがコピーされても同じ場所を指し続けられるよう、
 * セーブフォルダ直下に worldstitch_id.txt という小さなマーカーファイルを置き、
 * そこにUUID文字列を保存する方式を採る。
 *
 * 別セーブへのジャンプ機能(PendingTransfer)からは、セーブ間で共有される
 * 一時領域(saves/ の隣)を特定するためにも {@link #getSavesRoot(MinecraftServer)} を使う。
 */
public final class WorldIdentity {

    private static final String MARKER_FILE_NAME = "worldstitch_id.txt";

    private WorldIdentity() {
    }

    public static String getCurrentId(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return "unknown";
        }
        return getOrCreateId(serverLevel.getServer());
    }

    /**
     * 現在のセーブの表示名(ワールド一覧に出る「セーブ名」。level.dat の LevelName)を返す。
     * 内部識別用の {@link #getCurrentId} とは別物 ―― こちらはあくまで人間が読むための名前で、
     * セーブがリネームされれば変わりうる(目印帳に表示する接続先名として使う)。
     */
    public static String getCurrentDisplayName(Level level) {
        if (level.getServer() == null) {
            return "unknown";
        }
        return level.getServer().getWorldData().getLevelName();
    }

    /** サーバー(統合サーバー)から直接ワールドIDを取得・発行する。まだ世界(Level)が無い段階でも呼べる。 */
    public static String getOrCreateId(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path markerFile = worldRoot.resolve(MARKER_FILE_NAME);

        try {
            if (Files.exists(markerFile)) {
                String content = Files.readString(markerFile).trim();
                if (!content.isEmpty()) {
                    return content;
                }
            }
            Files.createDirectories(worldRoot);
            String newId = UUID.randomUUID().toString();
            Files.writeString(markerFile, newId);
            return newId;
        } catch (IOException e) {
            // 読み書きに失敗した場合は、セーブフォルダのパス自体を代用IDとして使う
            return worldRoot.toAbsolutePath().toString();
        }
    }

    /**
     * このセーブが属している saves/ フォルダそのものを返す。
     * (server.getWorldPath(ROOT) は ".../saves/&lt;levelId&gt;/." を指すため、
     * normalize() で "." を取り除いてから一つ上の階層を取る)
     */
    public static Path getSavesRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).normalize().getParent();
    }
}
