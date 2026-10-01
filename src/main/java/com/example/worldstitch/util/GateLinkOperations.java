package com.example.worldstitch.util;

import com.example.worldstitch.block.GateFrameBlock;
import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.config.WorldstitchConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * ゲートフレームの「目印(mark)」文字列 - ワールドID:次元ID:x:y:z:ワールド名 - の生成・解析と、
 * それを使ったリンク適用処理。/worldstitch コマンドと、GateLinkerItem のGUI画面の
 * 両方から共通で使う。
 *
 * 次元ID部分(例: minecraft:overworld, minecraft:the_nether, minecraft:the_end)は
 * 次元対応(Beta0.21)で追加した。末尾のワールド名部分は、目印帳でカーソルを合わせた時に
 * 「接続先セーブ名」を表示する機能(Beta0.2.6)のために追加した ―― リンクを作った時点の
 * セーブの表示名(level.dat の LevelName)をそのまま埋め込んでおくことで、相手セーブを
 * 開かなくても名前を表示できるようにしている(後からセーブ名を変えても、既存のリンクの
 * 表示はリンクした時点の名前のまま変わらない)。
 * どちらの追加も後方互換のため、それより前に発行された目印(4要素、6要素)も読める。
 */
public final class GateLinkOperations {

    private GateLinkOperations() {
    }

    /** 指定したフレームの目印文字列(ワールドID:次元ID:x:y:z:ワールド名)を作る。 */
    public static String buildMark(Level level, BlockPos framePos) {
        String worldId = WorldIdentity.getCurrentId(level);
        String worldName = WorldIdentity.getCurrentDisplayName(level);
        String dimensionId = GateFrameBlockEntity.dimensionIdFromKey(level.dimension());
        return worldId + ":" + dimensionId + ":" + framePos.getX() + ":" + framePos.getY() + ":" + framePos.getZ()
                + ":" + worldName;
    }

    /**
     * 目印文字列を解析する。形式が不正なら null。
     * 新形式(ワールドID:名前空間:パス:x:y:z:ワールド名、7要素。ワールド名にコロンが
     * 含まれていても最後の要素としてそのまま保持される)、ワールド名対応より前の
     * 6要素形式(ワールド名はワールドIDで代用)、次元対応より前の旧形式
     * (ワールドID:x:y:z、4要素。オーバーワールド扱い、ワールド名はワールドIDで代用)の
     * いずれも受け付ける。
     */
    public static ParsedMark parseMark(String mark) {
        String[] parts = mark.trim().split(":", 7);
        try {
            if (parts.length == 6 || parts.length == 7) {
                String worldId = parts[0];
                Identifier dimensionLocation = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionLocation);
                int x = Integer.parseInt(parts[3]);
                int y = Integer.parseInt(parts[4]);
                int z = Integer.parseInt(parts[5]);
                String worldName = parts.length == 7 ? parts[6] : worldId;
                return new ParsedMark(worldId, worldName, dimension, new BlockPos(x, y, z));
            }
            if (parts.length == 4) {
                String worldId = parts[0];
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                return new ParsedMark(worldId, worldId, Level.OVERWORLD, new BlockPos(x, y, z));
            }
            return null;
        } catch (RuntimeException e) {
            // 数値変換の失敗(NumberFormatException)や、次元IDの文字として不正な文字列を
            // 渡した場合の例外(実装によって型が変わり得る)をまとめて「不正な目印」として扱う。
            return null;
        }
    }

    /** オーバーワールド以外(ネザー・エンド等)を絡めた操作が、現在の設定で許可されているか。 */
    public static boolean isDimensionAllowed(ResourceKey<Level> dimension) {
        return dimension.equals(Level.OVERWORLD) || WorldstitchConfig.get().allowCrossDimensionTravel;
    }

    /**
     * sourceFrame から targetMark へのリンクを設定し、相手側への戻りリンクも設定する。
     *
     * 相手側が同じセーブ内にある場合(次元が違っても)は、サーバーから対象次元の
     * {@link ServerLevel} を取得し、その場で直接戻りリンクを設定する
     * (そうしないと、次にこのワールドを読み込み直すまで双方向リンクが開通しない)。
     * 相手側が別セーブの場合は、今は直接書き込めないので、
     * 相手セーブが次に読み込まれた時に適用されるよう {@link PendingFrameLink} で予約する。
     *
     * @return 適用結果(成功/ポータル未形成/次元またぎが設定で禁止/既にリンク済み)
     */
    public static LinkResult applyLink(ServerPlayer player, GateFrameBlockEntity sourceFrame, ParsedMark targetMark) {
        // 1つのゲート(繋がっている枠構造全体)につき1本のリンクしか持てないようにする。
        // 既にどこかがリンク済みなら、上書きせずに ALREADY_LINKED を返す
        // (目印帳側でも同じ状態を見て「リンクする」ボタンをグレーアウトするが、
        // コマンド経由の呼び出しにも同じ制約をかけるため、ここでも必ずチェックする)。
        if (PortalAreaHelper.findLinkedFrameInGroup(player.level(), sourceFrame.getBlockPos()) != null) {
            return LinkResult.ALREADY_LINKED;
        }

        ResourceKey<Level> currentDimension = player.level().dimension();
        if (!isDimensionAllowed(currentDimension) || !isDimensionAllowed(targetMark.dimension())) {
            return LinkResult.CROSS_DIMENSION_DISABLED;
        }

        String sourceDimensionId = GateFrameBlockEntity.dimensionIdFromKey(currentDimension);
        String targetDimensionId = GateFrameBlockEntity.dimensionIdFromKey(targetMark.dimension());
        String currentWorldName = WorldIdentity.getCurrentDisplayName(player.level());

        sourceFrame.setLink(targetMark.worldId(), targetMark.worldName(), targetDimensionId,
                targetMark.pos().getX(), targetMark.pos().getY(), targetMark.pos().getZ());
        boolean formed = GateFrameBlock.tryActivate(player.level(), sourceFrame.getBlockPos());

        String currentWorldId = WorldIdentity.getCurrentId(player.level());

        if (currentWorldId.equals(targetMark.worldId())) {
            // 同一セーブ内リンク: 次元が違っても、同じ統合サーバーが持つ別の ServerLevel を
            // 取得すれば直接設定できる
            ServerLevel targetLevel = player.level().getServer() != null
                    ? player.level().getServer().getLevel(targetMark.dimension())
                    : null;
            if (targetLevel != null) {
                BlockEntity targetEntity = targetLevel.getBlockEntity(targetMark.pos());
                if (targetEntity instanceof GateFrameBlockEntity targetFrame) {
                    targetFrame.setLink(currentWorldId, currentWorldName, sourceDimensionId,
                            sourceFrame.getBlockPos().getX(), sourceFrame.getBlockPos().getY(),
                            sourceFrame.getBlockPos().getZ());
                    GateFrameBlock.tryActivate(targetLevel, targetFrame.getBlockPos());
                }
            }
        } else {
            // 別セーブへのリンク: 相手セーブが次に読み込まれた時に適用されるよう予約する
            PendingFrameLink.stageReverseLink(
                    player.level().getServer(), targetMark.worldId(), targetMark.dimension(), targetMark.pos(),
                    currentWorldId, currentWorldName, currentDimension, sourceFrame.getBlockPos());
        }

        return formed ? LinkResult.LINKED : LinkResult.LINKED_BUT_PORTAL_NOT_FORMED;
    }

    /** プレイヤーが見ている先のゲートフレームを返す。見ていなければ null。 */
    public static GateFrameBlockEntity lookAtFrame(ServerPlayer player) {
        HitResult hit = player.pick(8.0, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            return null;
        }
        BlockEntity blockEntity = player.level().getBlockEntity(blockHit.getBlockPos());
        return blockEntity instanceof GateFrameBlockEntity frame ? frame : null;
    }

    /**
     * anyFramePosInGroup と物理的に繋がっている枠(1つのゲート構造)のどこかがリンクされていれば、
     * それを枠ブロック自体を壊さずに解除する。
     *
     * 1つの矩形の枠を構成する複数の GateFrameBlock のうち、実際にリンク情報を持つのは
     * /worldstitch mark・link を実行した時に見ていた1ブロックだけなので、
     * どこを右クリックしても解除できるよう {@link PortalAreaHelper#findLinkedFrameInGroup}
     * で実際にリンクされているブロックを探してから {@link #unlinkFrame} を呼ぶ。
     *
     * 破壊保護が有効なゲート(構造全体)は、誤操作防止のためリンク解除自体もできない
     * ようにする(枠を壊せない=繋ぎ直しできない、なのにリンクだけ外れてしまうと
     * かえって復旧しづらくなるため)。保護を外してから解除する必要がある。
     *
     * @return 解除結果(成功/元々未リンク/破壊保護中につき拒否)
     */
    public static UnlinkResult unlinkFrameGroup(Level level, BlockPos anyFramePosInGroup) {
        if (PortalAreaHelper.isGroupBreakProtected(level, anyFramePosInGroup)) {
            return UnlinkResult.BREAK_PROTECTED;
        }
        GateFrameBlockEntity linkedFrame = PortalAreaHelper.findLinkedFrameInGroup(level, anyFramePosInGroup);
        if (linkedFrame == null) {
            return UnlinkResult.NOT_LINKED;
        }
        return unlinkFrame(level, linkedFrame) ? UnlinkResult.UNLINKED : UnlinkResult.NOT_LINKED;
    }

    /**
     * frame のリンクを、枠ブロック自体を壊さずに解除する。
     *
     * 枠が壊れた時({@link GateFrameBlockEntity#preRemoveSideEffects})
     * と同じ考え方で、こちら側(このワールドにある枠+ポータル面)を後始末したうえで、
     * 相手側の枠についても後始末する: 同一セーブ内なら(次元が違っても)対象次元の
     * {@link ServerLevel} を取得してその場で直接、別セーブなら
     * {@link PendingFrameUnlink} で相手セーブが次に読み込まれた時に適用されるよう予約する。
     * これにより、意図せずリンク切れになってしまった片方の枠だけがポータルを保持し続ける
     * (=見た目には繋がっているのに転移すると相手が居ない)事態を防ぐ。
     *
     * また、このセーブ自身が今解除した相手先へ「離脱済み」だと記録されていた場合は、
     * その案内はもう無効なので消しておく(でないと相手側へ永久に案内され続けて詰む)。
     *
     * @return 解除前にリンクされていた場合は true。元々未リンクだった場合は何もせず false。
     */
    public static boolean unlinkFrame(Level level, GateFrameBlockEntity frame) {
        if (!frame.isLinked()) {
            return false;
        }

        String targetWorldId = frame.getTargetWorldId();
        ResourceKey<Level> targetDimension = frame.getTargetDimensionKey();
        BlockPos targetPos = frame.getTargetPos();

        // こちら側(このワールド内にある枠+つながっているポータル面)の後始末
        PortalAreaHelper.tearDownGateAt(level, frame.getBlockPos());

        String currentWorldId = WorldIdentity.getCurrentId(level);
        if (currentWorldId.equals(targetWorldId)) {
            // 相手側の枠も同じセーブ内にあるので、対象次元の ServerLevel を取得して直接後始末する
            ServerLevel targetLevel = level.getServer() != null ? level.getServer().getLevel(targetDimension) : null;
            if (targetLevel != null) {
                PortalAreaHelper.tearDownGateAt(targetLevel, targetPos);
            }
        } else if (level.getServer() != null) {
            // 別セーブ: 相手セーブが次に読み込まれた時に適用されるよう予約する
            PendingFrameUnlink.stage(level.getServer(), targetWorldId, targetDimension, targetPos);
        }

        if (level.getServer() != null && targetWorldId.equals(WorldResidency.getDepartedTo(level.getServer()))) {
            WorldResidency.clearDeparted(level.getServer());
        }

        return true;
    }

    /**
     * framePos が属するゲート構造の破壊保護をON/OFFし、リンク先(繋がっている相手のゲート)にも
     * 同じ状態を伝播させる。
     *
     * 片方だけ保護しても、繋ぎ先が保護されていなければ相手側から枠を壊されてリンクごと
     * 失われてしまう(=保護の意味が薄れる)ため、ゲートを保護する時は繋がっている相手も
     * 一緒に保護するのが自然。相手が同じセーブ内(次元が違うだけ)ならその場で直接、
     * 別セーブならそのセーブが次に読み込まれた時に適用されるよう {@link PendingFrameProtection}
     * で予約する。未リンクのゲートの場合は、このゲート自身の構造にだけ適用する。
     *
     * @return 適用後の保護状態(protect と同じ値を返すだけだが、呼び出し側の可読性のため)
     */
    public static boolean applyBreakProtectionToGroup(Level level, BlockPos framePos, boolean protect) {
        // まずこのゲート自身(構造全体)に適用する。
        PortalAreaHelper.setBreakProtectedForGroup(level, framePos, protect);

        // 繋がっている相手にも同じ状態を伝播させる(リンク情報は構造中の1ブロックだけが
        // 持っているので、まず実際にリンク情報を持つ枠を探す)。
        GateFrameBlockEntity linkedFrame = PortalAreaHelper.findLinkedFrameInGroup(level, framePos);
        if (linkedFrame == null) {
            return protect;
        }

        String targetWorldId = linkedFrame.getTargetWorldId();
        ResourceKey<Level> targetDimension = linkedFrame.getTargetDimensionKey();
        BlockPos targetPos = linkedFrame.getTargetPos();

        String currentWorldId = WorldIdentity.getCurrentId(level);
        if (currentWorldId.equals(targetWorldId)) {
            // 相手側の枠も同じセーブ内にあるので、対象次元の ServerLevel を取得して直接適用する
            ServerLevel targetLevel = level.getServer() != null ? level.getServer().getLevel(targetDimension) : null;
            if (targetLevel != null) {
                PortalAreaHelper.setBreakProtectedForGroup(targetLevel, targetPos, protect);
            }
        } else if (level.getServer() != null) {
            // 別セーブ: 相手セーブが次に読み込まれた時に適用されるよう予約する
            PendingFrameProtection.stage(level.getServer(), targetWorldId, targetDimension, targetPos, protect);
        }

        return protect;
    }

    public record ParsedMark(String worldId, String worldName, ResourceKey<Level> dimension, BlockPos pos) {
    }

    public enum LinkResult {
        LINKED,
        LINKED_BUT_PORTAL_NOT_FORMED,
        CROSS_DIMENSION_DISABLED,
        ALREADY_LINKED
    }

    public enum UnlinkResult {
        UNLINKED,
        NOT_LINKED,
        BREAK_PROTECTED
    }
}
