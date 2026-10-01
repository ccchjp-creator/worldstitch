package com.example.worldstitch.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MODのコンフィグファイル(config/worldstitch.json)。
 *
 * MOD初期化のタイミング({@code WorldstitchMod#onInitialize})で一度だけ読み込む。
 * ファイルが存在しない場合は、ここに書かれているデフォルト値でそのまま新規作成する。
 * このMOD自体がシングルプレイ専用という前提のため、ワールドごとではなく、
 * 起動しているMinecraftインストール(configフォルダ)ごとに1つの設定として扱う。
 *
 * 各項目の意味は、対応する既存コードに残していたハードコード値をそのまま
 * 設定可能にしたもの。設定ファイルを直接編集して調整できる。
 */
public final class WorldstitchConfig {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "worldstitch.json";

    private static WorldstitchConfig instance = new WorldstitchConfig();

    // --- 次元をまたいだワープ ---

    /**
     * false にすると、オーバーワールド以外の次元(ネザー・エンド等)を
     * 絡めたワープを一切禁止する。
     *
     * 無効時は、ネザーやエンドでゲートの目印帳を右クリックしたり
     * /worldstitch コマンドを実行しようとした時点で、案内メッセージが出て弾かれる
     * (オーバーワールドでは通常通り使える)。
     * 既にリンク済みの枠を通ろうとした場合も、転移先が別次元であればここで弾かれる。
     */
    public boolean allowCrossDimensionTravel = true;

    // --- 別セーブデータをまたいだワープ ---

    /**
     * false にすると、別のセーブデータへのワープ(高速ロード切り替え)を一切禁止する。
     * 同じセーブ内(同一ワールド内・別次元を含む)でのワープには影響しない。
     */
    public boolean allowCrossSaveTravel = true;

    // --- 離脱済みセーブへの直接アクセス ---

    /**
     * false(既定)の場合、「別セーブへ離脱済み」と記録されているセーブをワールド一覧から
     * 直接開こうとすると、実際に居るべきセーブへ自動的に案内される(離脱前の古い状態のまま
     * 遊んでしまい、実際の最新状態と食い違うのを防ぐための保護)。
     *
     * true にすると、この自動案内を行わず、離脱記録が残っていてもそのセーブをそのまま
     * 直接プレイできるようにする(記録自体は消さず、ログイン時に案内メッセージだけ出す)。
     * /worldstitch residency allow|block でも切り替えられる。
     */
    public boolean allowEnteringDepartedSave = false;

    // --- ポータルのクールダウン ---

    /**
     * 転移直後、再発火を防ぐための猶予(tick)。20tick = 1秒。
     * バニラのネザーポータルは既定80だが、本MODは既定60(3秒)。
     */
    public int portalCooldownTicks = 60;

    // --- ゲート枠のサイズ制限(ネザーポータルと同じ考え方) ---

    /** ゲートの内寸として許可する最小幅。 */
    public int minGateInnerWidth = 2;
    /** ゲートの内寸として許可する最小高さ。 */
    public int minGateInnerHeight = 3;
    /** ゲートの内寸(幅・高さとも)として許可する最大サイズ。 */
    public int maxGateInnerSize = 21;

    // --- 戻りリンクの再試行 ---

    /**
     * 転移先セーブ・次元が読み込まれた直後、周辺チャンクの読み込み待ちで
     * ポータル形成のリトライを続ける回数の上限(END_SERVER_TICK ごとに1回試行)。
     * 既定200 = およそ10秒。
     */
    public int reverseLinkMaxRetryTicks = 200;

    // --- 枠ブロックの破壊保護 ---

    /**
     * false にすると、枠ブロックの破壊保護機能そのものを無効化する。
     * 個別のゲートに保護が設定されていても、通常のブロックとして誰でも壊せるようになる。
     *
     * 既定は true(機能自体は有効)。実際にどのゲートが保護されるかは、
     * ゲートごとの設定(ゲートの目印帳の「破壊保護」ボタン、または
     * 見ている枠に対する /worldstitch protect コマンド)で個別に決める。
     */
    public boolean frameBreakProtectionEnabled = true;

    private WorldstitchConfig() {
    }

    public static WorldstitchConfig get() {
        return instance;
    }

    /** MOD初期化時に一度だけ呼ぶ。ファイルが無ければデフォルト値で新規作成する。 */
    public static void load() {
        Path path = configPath();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                WorldstitchConfig loaded = GSON.fromJson(reader, WorldstitchConfig.class);
                if (loaded != null) {
                    instance = loaded;
                    LOGGER.info("[Worldstitch] Loaded config from {}", path);
                    return;
                }
            } catch (IOException | JsonSyntaxException e) {
                LOGGER.warn("[Worldstitch] Failed to read config at {}; using defaults", path, e);
            }
        }
        instance = new WorldstitchConfig();
        save();
    }

    /** 現在の設定値をファイルへ書き出す(欠けていた項目の補完・新規作成にも使う)。 */
    public static void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("[Worldstitch] Failed to save config to {}", path, e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
