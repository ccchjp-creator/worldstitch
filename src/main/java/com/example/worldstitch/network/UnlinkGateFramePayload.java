package com.example.worldstitch.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.worldstitch.WorldstitchMod;

/**
 * クライアント→サーバー: 「このフレームのリンクを解除してください」という依頼。
 *
 * ゲートの目印帳の画面で「リンク解除」ボタンを押した時に送られる。
 * 枠ブロック自体は壊さず、リンク情報とポータル面だけを取り除く
 * (相手側が別セーブの場合は、そちらにも {@link com.example.worldstitch.util.PendingFrameUnlink} 経由で伝わる)。
 *
 * framePos: リンクを解除したいフレームの座標(画面を開いた時のフレームと同じ)
 */
public record UnlinkGateFramePayload(BlockPos framePos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UnlinkGateFramePayload> TYPE =
            new CustomPacketPayload.Type<>(WorldstitchMod.id("unlink_gate_frame"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UnlinkGateFramePayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, UnlinkGateFramePayload::framePos,
            UnlinkGateFramePayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
