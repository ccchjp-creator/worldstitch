package com.example.worldstitch.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.worldstitch.WorldstitchMod;

/**
 * サーバー→クライアント: 「このゲートフレームのリンク編集画面を開いてください」という通知。
 *
 * framePos: 開いた対象のフレームの座標(リンク結果の送り返し先を特定するために使う)
 * thisMark: そのフレーム自身の目印(ワールドID:次元ID:x:y:z:ワールド名)。画面内の「コピー」ボタンで使う。
 * linked: このフレームが現在リンク済みかどうか。画面に「リンク解除」ボタンを出すかどうかに使う。
 * targetSummary: リンク済みの場合の「接続先セーブ名 (x, y, z)」表示用文字列。未リンクなら空文字列。
 * currentDescription: このゲートに現在設定されている説明文(未設定なら空文字列)。
 * breakProtected: このゲート(構造全体)が現在破壊保護されているかどうか。
 *                 画面の「破壊保護」ボタンの初期表示に使う。
 */
public record OpenGateLinkScreenPayload(BlockPos framePos, String thisMark, boolean linked,
                                         String targetSummary, String currentDescription,
                                         boolean breakProtected)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenGateLinkScreenPayload> TYPE =
            new CustomPacketPayload.Type<>(WorldstitchMod.id("open_gate_link_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenGateLinkScreenPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenGateLinkScreenPayload::framePos,
            ByteBufCodecs.STRING_UTF8, OpenGateLinkScreenPayload::thisMark,
            ByteBufCodecs.BOOL, OpenGateLinkScreenPayload::linked,
            ByteBufCodecs.STRING_UTF8, OpenGateLinkScreenPayload::targetSummary,
            ByteBufCodecs.STRING_UTF8, OpenGateLinkScreenPayload::currentDescription,
            ByteBufCodecs.BOOL, OpenGateLinkScreenPayload::breakProtected,
            OpenGateLinkScreenPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
