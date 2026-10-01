package com.example.worldstitch.block.entity;

import com.example.worldstitch.WorldstitchMod;
import com.example.worldstitch.block.GateFrameBlock;
import com.example.worldstitch.util.PendingFrameUnlink;
import com.example.worldstitch.util.PortalAreaHelper;
import com.example.worldstitch.util.WorldIdentity;
import com.example.worldstitch.util.WorldResidency;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * ゲートの枠(フレーム)が持つリンク情報。
 *
 * 「フォルダパス」ではなく「ワールドID(UUID文字列)」で行き先を指定することで、
 * 将来ワールドが増えても・フォルダ名が変わっても対応できるようにしている。
 * さらに「次元ID(例: minecraft:the_nether)」も併せて持たせることで、
 * 同じセーブ内・別セーブ間のどちらでも、オーバーワールド以外の次元を
 * リンク先に指定できるようにしている。
 */
public class GateFrameBlockEntity extends BlockEntity {

    /** 次元IDを保存していなかった古いデータ(次元対応より前)を読み込んだ時のデフォルト値。 */
    private static final String DEFAULT_DIMENSION_ID = "minecraft:overworld";

    @Nullable
    private String targetWorldId;
    /** リンク先セーブの表示名(level.dat の LevelName)。リンクを作った時点のスナップショット。 */
    private String targetWorldName = "";
    private String targetDimensionId = DEFAULT_DIMENSION_ID;
    private int targetX;
    private int targetY;
    private int targetZ;
    private boolean linked = false;

    /** このゲートが「どこに繋がっているか」などをプレイヤー自身が書ける自由なメモ(目印帳で設定)。 */
    private String linkDescription = "";

    /**
     * この枠が「破壊保護」されているかどうか(目印帳の「破壊保護」ボタン、または
     * /worldstitch protect コマンドで設定)。true の場合、このゲートを構成する
     * 枠ブロック(実際に形成されているポータル面を介してつながっている範囲全体)は、
     * WorldstitchConfig#frameBreakProtectionEnabled が有効な限り、
     * プレイヤーが壊そうとしても弾かれる(詳細は PortalAreaHelper 側の group 系メソッド参照)。
     */
    private boolean breakProtected = false;

    // このフレームに対応する内部ポータル面の範囲(破壊時に一緒に消すため)
    private BlockPos portalMin;
    private BlockPos portalMax;

    public GateFrameBlockEntity(BlockPos pos, BlockState state) {
        super(WorldstitchMod.GATE_FRAME_BLOCK_ENTITY, pos, state);
    }

    public void setLink(String targetWorldId, String targetWorldName, String targetDimensionId, int x, int y, int z) {
        this.targetWorldId = targetWorldId;
        this.targetWorldName = targetWorldName;
        this.targetDimensionId = targetDimensionId;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.linked = true;
        setChanged();
    }

    public void setPortalArea(BlockPos min, BlockPos max) {
        this.portalMin = min;
        this.portalMax = max;
        setChanged();
    }

    /** リンク情報を消し、未リンクの枠に戻す(ポータル面の破棄時に呼ぶ)。 */
    public void clearLink() {
        this.targetWorldId = null;
        this.targetWorldName = "";
        this.targetDimensionId = DEFAULT_DIMENSION_ID;
        this.linked = false;
        setChanged();
    }

    /** 記録していたポータル面の範囲情報を消す(ポータル面の破棄時に呼ぶ)。 */
    public void clearPortalArea() {
        this.portalMin = null;
        this.portalMax = null;
        setChanged();
    }

    public boolean isLinked() {
        return linked && targetWorldId != null;
    }

    @Nullable
    public String getTargetWorldId() {
        return targetWorldId;
    }

    /** リンク先セーブの表示名(リンクを作った時点のスナップショットなので、後からリネームされてもここは変わらない)。 */
    public String getTargetWorldName() {
        return targetWorldName;
    }

    /** リンク先の次元ID(例: "minecraft:the_nether")。未リンクなら意味を持たない。 */
    public String getTargetDimensionId() {
        return targetDimensionId;
    }

    /** リンク先の次元をキーとして取得する。ID文字列が不正な場合はオーバーワールド扱いにする。 */
    public ResourceKey<Level> getTargetDimensionKey() {
        return dimensionKeyFromId(targetDimensionId);
    }

    public BlockPos getTargetPos() {
        return new BlockPos(targetX, targetY, targetZ);
    }

    /** このゲートに設定されている説明文(未設定なら空文字列)。 */
    public String getLinkDescription() {
        return linkDescription;
    }

    /** このゲートの説明文を設定する(目印帳から呼ぶ)。空文字列を渡せば消せる。 */
    public void setLinkDescription(String linkDescription) {
        this.linkDescription = linkDescription == null ? "" : linkDescription;
        setChanged();
    }

    /** この枠単体が破壊保護されているかどうか。ゲート構造全体としての判定は PortalAreaHelper 側を使う。 */
    public boolean isBreakProtected() {
        return breakProtected;
    }

    /** この枠単体の破壊保護フラグを設定する。ゲート構造全体への反映は呼び出し側の責任。 */
    public void setBreakProtected(boolean breakProtected) {
        this.breakProtected = breakProtected;
        setChanged();

        // ブロックエンティティのNBTはクライアントへ同期していないため、クライアント側でも
        // 同じ値を見られるよう、ブロックステートの方も合わせて書き換える(バニラの通常の
        // ブロック更新の仕組みでクライアントへ自動的に伝わる。詳細は GateFrameBlock 参照)。
        Level currentLevel = this.getLevel();
        if (currentLevel != null && !currentLevel.isClientSide()) {
            BlockState state = currentLevel.getBlockState(this.getBlockPos());
            if (state.hasProperty(GateFrameBlock.PROTECTED) && state.getValue(GateFrameBlock.PROTECTED) != breakProtected) {
                currentLevel.setBlock(this.getBlockPos(), state.setValue(GateFrameBlock.PROTECTED, breakProtected),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    @Nullable
    public BlockPos getPortalMin() {
        return portalMin;
    }

    @Nullable
    public BlockPos getPortalMax() {
        return portalMax;
    }

    /** 次元IDの文字列("名前空間:パス")を ResourceKey&lt;Level&gt; に変換する。不正なら Level.OVERWORLD。 */
    public static ResourceKey<Level> dimensionKeyFromId(@Nullable String dimensionId) {
        if (dimensionId != null) {
            Identifier parsed = Identifier.tryParse(dimensionId);
            if (parsed != null) {
                return ResourceKey.create(Registries.DIMENSION, parsed);
            }
        }
        return Level.OVERWORLD;
    }

    /** ResourceKey&lt;Level&gt; を、目印文字列やNBTに保存する形式の次元ID文字列に変換する。 */
    public static String dimensionIdFromKey(ResourceKey<Level> dimensionKey) {
        return dimensionKey.identifier().toString();
    }

    /**
     * このブロックエンティティがワールドから取り除かれる直前に呼ばれる
     * (Minecraft 1.21.5 以降、Block#onRemove から分離された新しいフック)。
     *
     * 枠が破壊された時、この枠が属していたポータル面をまとめて消し、リンクも解除する。
     * リンク先が別セーブの場合は {@link PendingFrameUnlink} 経由でそちらにも予約する。
     * リンク先が同じセーブ内の別次元の場合は、対象次元の {@link ServerLevel} を
     * サーバーから取得して直接後始末する。
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);

        Level level = this.getLevel();
        if (level == null || level.isClientSide()) {
            return;
        }

        // 共有している枠列を介して隣接ゲートが巻き込まれた場合も考慮し、
        // 見つかった全てのリンク情報についてそれぞれ独立に後始末を行う。
        List<PortalAreaHelper.GateLinkInfo> linkInfos = PortalAreaHelper.tearDownAfterFrameBreak(level, pos, this);

        if (!linkInfos.isEmpty() && level instanceof ServerLevel serverLevel) {
            MinecraftServer server = serverLevel.getServer();
            String currentWorldId = WorldIdentity.getCurrentId(level);

            for (PortalAreaHelper.GateLinkInfo linkInfo : linkInfos) {
                if (currentWorldId.equals(linkInfo.worldId())) {
                    // 同一セーブ内リンク: 相手側の枠も今このサーバーの中にあるので直接後始末する
                    // (次元が違っても、同じ統合サーバーが持つ別の ServerLevel を取得すればよい)
                    ServerLevel targetLevel = server.getLevel(linkInfo.dimension());
                    if (targetLevel != null) {
                        PortalAreaHelper.tearDownGateAt(targetLevel, linkInfo.pos());
                    }
                } else {
                    // 別セーブへのリンク: 相手側が次に読み込まれた時に後始末されるよう予約する
                    PendingFrameUnlink.stage(server, linkInfo.worldId(), linkInfo.dimension(), linkInfo.pos());
                }

                // このセーブ自身が、今リンクを解除した相手先へ「離脱済み」だと記録されていた場合、
                // その案内はもう無効なので消しておく(でないと相手側へ永久に案内され続けて詰む)。
                if (linkInfo.worldId().equals(WorldResidency.getDepartedTo(server))) {
                    WorldResidency.clearDeparted(server);
                }
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("Linked", linked);
        if (targetWorldId != null) {
            output.putString("TargetWorldId", targetWorldId);
            output.putString("TargetWorldName", targetWorldName);
            output.putString("TargetDimension", targetDimensionId);
            output.putInt("TargetX", targetX);
            output.putInt("TargetY", targetY);
            output.putInt("TargetZ", targetZ);
        }
        if (!linkDescription.isEmpty()) {
            output.putString("Description", linkDescription);
        }
        if (breakProtected) {
            output.putBoolean("BreakProtected", true);
        }
        if (portalMin != null && portalMax != null) {
            output.putLong("PortalMin", portalMin.asLong());
            output.putLong("PortalMax", portalMax.asLong());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.linked = input.getBooleanOr("Linked", false);
        input.getString("TargetWorldId").ifPresent(worldId -> {
            this.targetWorldId = worldId;
            // 次元対応・説明文対応より前に保存されたデータには無い項目なので、それぞれ既定値で補う。
            this.targetWorldName = input.getString("TargetWorldName").orElse("");
            this.targetDimensionId = input.getString("TargetDimension").orElse(DEFAULT_DIMENSION_ID);
            this.targetX = input.getIntOr("TargetX", 0);
            this.targetY = input.getIntOr("TargetY", 0);
            this.targetZ = input.getIntOr("TargetZ", 0);
        });
        this.linkDescription = input.getString("Description").orElse("");
        this.breakProtected = input.getBooleanOr("BreakProtected", false);
        input.getLong("PortalMin").ifPresent(min -> this.portalMin = BlockPos.of(min));
        input.getLong("PortalMax").ifPresent(max -> this.portalMax = BlockPos.of(max));
    }
}
