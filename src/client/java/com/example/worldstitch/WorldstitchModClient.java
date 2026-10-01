package com.example.worldstitch;

import com.example.worldstitch.client.GateLinkScreen;
import com.example.worldstitch.client.util.ClientWorldSwitcher;
import com.example.worldstitch.network.OpenGateLinkScreenPayload;
import com.example.worldstitch.util.ClientWorldSwitchBridge;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * クライアント側の初期化クラス。
 */
public class WorldstitchModClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // サーバー(統合サーバー)側の共通コードから、別セーブへの切り替えを依頼できるようにする
        ClientWorldSwitchBridge.setHandler(ClientWorldSwitcher::requestSwitchToWorldId);

        // GateLinkerItem でフレームを右クリックした時、サーバーから届く「画面を開いて」通知を受け取る
        ClientPlayNetworking.registerGlobalReceiver(OpenGateLinkScreenPayload.TYPE, (payload, context) ->
                context.client().setScreenAndShow(
                        new GateLinkScreen(payload.framePos(), payload.thisMark(), payload.linked(),
                                payload.targetSummary(), payload.currentDescription(), payload.breakProtected())));

        // ポータル面の半透明描画について:
        // 26.1以降、ブロックのレンダーレイヤー(不透明/カットアウト/半透明)は
        // テクスチャのピクセル(アルファ値)から自動判定されるようになった
        // (BlockRenderLayerMap 等の手動登録APIは廃止され、存在しない)。
        // gate_portal.png 側に半透明のアルファ値を持たせてあるので、
        // ここでは何も登録しなくても自動的に半透明として描画される。
    }
}
