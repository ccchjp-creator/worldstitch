package com.example.worldstitch.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.worldstitch.WorldstitchMod;

/**
 * クライアント→サーバー: 「このゲートの説明文を設定してください」という依頼。
 *
 * ゲートの目印帳の画面で説明欄に入力して確定した時に送られる。
 * framePos は右クリックした枠の座標(必ずしも実際にリンク情報を持つ枠とは限らない ―
 * 同じ構造の中で実際にリンクされている枠を、サーバー側で改めて探して設定する)。
 * description は空文字列でもよい(説明を消すことに使う)。
 */
public record SetGateDescriptionPayload(BlockPos framePos, String description) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetGateDescriptionPayload> TYPE =
            new CustomPacketPayload.Type<>(WorldstitchMod.id("set_gate_description"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetGateDescriptionPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SetGateDescriptionPayload::framePos,
            ByteBufCodecs.STRING_UTF8, SetGateDescriptionPayload::description,
            SetGateDescriptionPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
