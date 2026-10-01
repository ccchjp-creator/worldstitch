package com.example.worldstitch.block;

import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.util.PendingFrameUnlink;
import com.example.worldstitch.util.PortalAreaHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import org.jetbrains.annotations.Nullable;

/**
 * ポータルの「枠」を構成するブロック。
 * ネザーポータルの黒曜石に相当する。
 *
 * 枠を組んだだけではポータル面は発生せず、
 * GateLinkerItem やコマンド(/worldstitch link)でリンクが完了した時にはじめて {@link PortalAreaHelper} が
 * 矩形をスキャンし、有効なら内部に gate_portal ブロックを敷き詰める。
 *
 * 枠ブロックが1つでも破壊されると、その枠が属していたポータル面は
 * (壊れた1マスに限らず)まとめて消え、リンクも解除される。リンク先が別セーブの場合は
 * {@link PendingFrameUnlink} 経由でそちらにも同じ後始末を予約する。
 * (実際の後始末処理は {@link GateFrameBlockEntity#preRemoveSideEffects} 側で行っている。
 *  1.21.5 以降、ブロックエンティティ自身のデータを使った破壊時処理は
 *  Block 側の onRemove ではなく BlockEntity#preRemoveSideEffects で行うのが正しい場所になったため)
 */
public class GateFrameBlock extends Block implements EntityBlock {

    /**
     * この枠が「破壊保護」されているかどうかを表すブロックステート。
     *
     * 値の正本は {@link GateFrameBlockEntity#isBreakProtected()}(NBTに保存される)で、
     * {@link GateFrameBlockEntity#setBreakProtected} が呼ばれるたびにこちらへも反映される。
     * ブロックエンティティのNBTはこのMODでは基本的にクライアントへ同期していないため、
     * わざわざブロックステートとしても持たせているのは、クライアント側でも(=バニラの
     * ブロック更新の仕組みにただ乗りする形で、追加の通信を書かずに)同じ値を見られるようにし、
     * 「殴った瞬間(採掘バーが1ミリも進む前)にキャンセルする」処理を両側で正しく動かすため
     * (硬度=destroyTime 自体はブロックのプロパティに焼き込まれた静的な値で、同じブロックの
     * まま動的に変えるための一般的な公開APIが無いため、この方式を取っている。
     * WorldstitchMod の AttackBlockCallback 登録処理を参照)。
     */
    public static final BooleanProperty PROTECTED = BooleanProperty.create("protected");

    public GateFrameBlock(Properties settings) {
        super(settings);
        this.registerDefaultState(this.stateDefinition.any().setValue(PROTECTED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PROTECTED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GateFrameBlockEntity(pos, state);
    }

    /**
     * GateLinkerItem やコマンド(/worldstitch link)側から呼ばれる。
     * まだポータル面が形成されていなければ形成を試みる。
     * @return 形成に成功した/既に形成済みなら true
     */
    public static boolean tryActivate(Level world, BlockPos framePos) {
        return PortalAreaHelper.tryFormPortal(world, framePos);
    }
}
