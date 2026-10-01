package com.example.worldstitch.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.example.worldstitch.WorldstitchMod;

/**
 * クライアント→サーバー: 「このゲートの破壊保護を切り替えてください」という依頼。
 *
 * ゲートの目印帳の画面で「破壊保護」ボタンを押した時に送られる。
 * サーバー側は現在の保護状態を見て反転させ、ゲート構造全体
 * (実際に形成されているポータル面を介してつながっている枠ブロック全部)に反映する
 * (詳細は {@link com.example.worldstitch.util.PortalAreaHelper#setBreakProtectedForGroup} 参照)。
 *
 * framePos: 画面を開いた時のフレームの座標(必ずしも実際に保護フラグを持つ枠とは限らない)。
 */
public record ToggleFrameBreakProtectionPayload(BlockPos framePos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ToggleFrameBreakProtectionPayload> TYPE =
            new CustomPacketPayload.Type<>(WorldstitchMod.id("toggle_frame_break_protection"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleFrameBreakProtectionPayload> CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ToggleFrameBreakProtectionPayload::framePos,
                    ToggleFrameBreakProtectionPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
