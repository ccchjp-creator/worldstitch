package com.example.worldstitch.util;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * 別セーブへの切り替え要求を、サーバー側の共通コードからクライアント側の実処理へ橋渡しする。
 *
 * シングルプレイでは統合サーバーとクライアントが同一JVM内で動くため直接呼び出すことも技術的には可能だが、
 * common(共通)コードから net.minecraft.client.* を直接参照すると、
 * ディディケートサーバー環境でそのクラスがロードできず起動できなくなる。
 * そのため間に薄いブリッジを挟み、実体(Consumer)はクライアント初期化時
 * ({@code WorldstitchModClient}) にのみ設定する。
 */
public final class ClientWorldSwitchBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Consumer<String> handler = null;

    private ClientWorldSwitchBridge() {
    }

    public static void setHandler(Consumer<String> newHandler) {
        handler = newHandler;
    }

    public static void requestSwitch(String targetWorldId) {
        Consumer<String> current = handler;
        if (current == null) {
            LOGGER.warn("[Worldstitch] No client world-switch handler registered (dedicated server?), targetWorldId={}",
                    targetWorldId);
            return;
        }
        current.accept(targetWorldId);
    }
}
