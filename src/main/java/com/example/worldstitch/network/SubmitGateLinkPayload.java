package com.example.worldstitch.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.worldstitch.WorldstitchMod;

/**
 * クライアント→サーバー: 「このフレームに、貼り付けた目印でリンクしてください」という依頼。
 *
 * framePos: リンク元にするフレームの座標(画面を開いた時のフレームと同じ)
 * pastedMark: プレイヤーが画面に貼り付けた目印の文字列(ワールドID:x:y:z)
 */
public record SubmitGateLinkPayload(BlockPos framePos, String pastedMark) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SubmitGateLinkPayload> TYPE =
            new CustomPacketPayload.Type<>(WorldstitchMod.id("submit_gate_link"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SubmitGateLinkPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SubmitGateLinkPayload::framePos,
            ByteBufCodecs.STRING_UTF8, SubmitGateLinkPayload::pastedMark,
            SubmitGateLinkPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
