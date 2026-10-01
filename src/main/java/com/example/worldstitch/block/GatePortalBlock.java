package com.example.worldstitch.block;

import com.example.worldstitch.WorldstitchMod;
import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.util.PendingPortalRecolor;
import com.example.worldstitch.util.PortalAreaHelper;
import com.example.worldstitch.util.PortalTeleporter;
import com.example.worldstitch.util.WorldIdentity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InsideBlockEffectApplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * ポータルの「内側の面」を構成するブロック。
 * 見た目・挙動はバニラのネザーポータルに寄せてあり、軸(X/Z)を
 * {@link BlockStateProperties#HORIZONTAL_AXIS} として、
 * 染料の色を {@link #COLOR} として持つ(デフォルトは白)。
 * (テクスチャ・モデルは軸×色の組み合わせごとに worldstitch:block/gate_portal_<色> を参照している。
 *  半透明に見えるのは、テクスチャ自体にアルファ値を持たせているため
 *  ―26.1以降、ブロックのレンダーレイヤーはテクスチャのアルファ値から自動判定されるようになり、
 *  以前のような手動登録(BlockRenderLayerMap等)は不要かつ廃止されている)。
 *
 * GateFrameBlock で組まれた枠の内側に、PortalAreaHelper によって
 * まとめて敷き詰められる。プレイヤーやMob・アイテムエンティティなどがこのブロックに
 * 触れると、対応する GateFrameBlockEntity のリンク先へ転移を試みる
 * (別セーブへのリンクへの転移だけはプレイヤー専用。詳細は PortalTeleporter 参照)。
 * 染料を持って右クリックすると、つながっているポータル面全体の色が変わる。
 *
 * 起動(=連続転移)を防ぐため、バニラのネザーポータルと同じ仕組み
 * ({@link Entity#isOnPortalCooldown()} / {@link Entity#setPortalCooldown(int)}) を
 * そのまま流用している。これは通常のエンティティ処理(baseTick)で毎ティック自動的に
 * カウントダウンされ、かつエンティティの通常のNBT("PortalCooldown"タグ)の一部として
 * 保存されるため、別セーブへの転移(プレイヤーデータのスナップショット)をまたいでも
 * 自動的に引き継がれる。実際にクールダウンを仕込むのは PortalTeleporter 側。
 *
 * さらに、クールダウンが明けた後もポータルに触れたまま(=移動していない)場合は
 * 再度ワープしないようにしている。ネザーポータルと同じく、一度ポータルから離れて
 * 触れ直すまでは発動しない(詳細は touchingThisTick / awaitingExit を参照)。
 */
public class GatePortalBlock extends Block {

    public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);

    public GatePortalBlock(Properties settings) {
        super(settings);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, Direction.Axis.X)
                .setValue(COLOR, DyeColor.WHITE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_AXIS, COLOR);
    }

    /**
     * 染料を持ってこのブロックを右クリックした時、つながっているポータル面全体の色を変える。
     * リンク先のポータルにも同じ色を伝える(同一ワールドならその場で、別セーブなら予約で)。
     */
    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hitResult) {
        DyeColor color = stack.get(DataComponents.DYE);
        if (color == null) {
            return InteractionResult.TRY_WITH_EMPTY_HAND;
        }
        if (state.getValue(COLOR) == color) {
            return InteractionResult.SUCCESS;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        recolorConnectedPortal(level, pos, color);
        propagateColorToLinkedGate(level, pos, color);

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        return InteractionResult.SUCCESS;
    }

    /**
     * このポータル面が属する枠がリンクされていれば、リンク先のポータルにも同じ色を伝える。
     * 同一ワールド内なら今このワールドの中にもう存在しているのでその場で直接塗り替え、
     * 別セーブなら {@link PendingPortalRecolor} で、相手セーブが次に読み込まれた時に
     * 適用されるよう予約する。
     */
    private void propagateColorToLinkedGate(Level level, BlockPos portalPos, DyeColor color) {
        GateFrameBlockEntity frame = findOwningFrame(level, portalPos);
        if (frame == null || !frame.isLinked()) {
            return;
        }

        String currentWorldId = WorldIdentity.getCurrentId(level);
        String targetWorldId = frame.getTargetWorldId();
        BlockPos targetFramePos = frame.getTargetPos();

        if (currentWorldId.equals(targetWorldId) && level.getServer() != null) {
            // 同一セーブ内リンク: 次元が違っても、同じ統合サーバーが持つ別の ServerLevel を
            // 取得すればその場で直接塗り替えられる
            ServerLevel targetLevel = level.getServer().getLevel(frame.getTargetDimensionKey());
            if (targetLevel != null) {
                BlockPos targetPortalCell = findAdjacentPortalCell(targetLevel, targetFramePos);
                if (targetPortalCell != null) {
                    recolorConnectedPortal(targetLevel, targetPortalCell, color);
                }
            }
        } else if (level instanceof ServerLevel serverLevel) {
            // 別セーブへのリンク: 相手セーブが次に読み込まれた時に適用されるよう予約する
            PendingPortalRecolor.stage(serverLevel.getServer(), targetWorldId, frame.getTargetDimensionKey(),
                    targetFramePos, color);
        }
    }

    /** framePos に隣接するポータル面ブロックを1つ探す(見つからなければ null)。 */
    private static BlockPos findAdjacentPortalCell(Level world, BlockPos framePos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = framePos.relative(direction);
            if (world.getBlockState(neighbor).is(WorldstitchMod.GATE_PORTAL_BLOCK)) {
                return neighbor;
            }
        }
        return null;
    }

    /**
     * start につながっているポータル面ブロックをすべて辿って、軸(向き)は変えずに色だけ塗り替える。
     */
    public static void recolorConnectedPortal(Level world, BlockPos start, DyeColor color) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);

        int guard = 0;
        while (!queue.isEmpty() && guard++ < 8192) {
            BlockPos current = queue.poll();
            BlockState currentState = world.getBlockState(current);
            if (!currentState.is(WorldstitchMod.GATE_PORTAL_BLOCK)) {
                continue;
            }

            world.setBlockAndUpdate(current, currentState.setValue(COLOR, color));

            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (visited.add(neighbor) && world.getBlockState(neighbor).is(WorldstitchMod.GATE_PORTAL_BLOCK)) {
                    queue.add(neighbor);
                }
            }
        }
    }

    /**
     * クライアント側で毎フレーム、近くのブロックに対してランダムに呼ばれる
     * (呼ばれるのはクライアントのみ。パーティクル・環境音などの見た目専用の処理はここに書く)。
     * バニラのネザーポータル({@code NetherPortalBlock#animateTick})とほぼ同じ内容。
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(100) == 0) {
            level.playLocalSound(pos, SoundEvents.PORTAL_AMBIENT, SoundSource.BLOCKS,
                    0.5F, random.nextFloat() * 0.4F + 0.8F, false);
        }

        for (int i = 0; i < 4; i++) {
            double x = pos.getX() + random.nextDouble();
            double y = pos.getY() + random.nextDouble();
            double z = pos.getZ() + random.nextDouble();
            double vx = (random.nextDouble() - 0.5D) * 0.5D;
            double vy = (random.nextDouble() - 0.5D) * 0.5D;
            double vz = (random.nextDouble() - 0.5D) * 0.5D;
            int side = random.nextInt(2) * 2 - 1;

            if (!level.getBlockState(pos.west()).is(this) && !level.getBlockState(pos.east()).is(this)) {
                x = pos.getX() + 0.5D + 0.25D * side;
                vx = random.nextFloat() * 2.0F * side;
            } else {
                z = pos.getZ() + 0.5D + 0.25D * side;
                vz = random.nextFloat() * 2.0F * side;
            }

            level.addParticle(ParticleTypes.PORTAL, x, y, z, vx, vy, vz);
        }
    }

    /**
     * 「転移直後、クールダウンが切れた後もポータルに触れたままだと再度ワープしてしまう」のを防ぐための
     * 状態管理。バニラのネザーポータルは専用の PortalProcessor(エンティティ側でその場に居続けているかを
     * 毎ティック追跡する仕組み)でこれを実現しているが、Block 側だけで完結させたいので、簡易的に
     * 「直近のワープでまだこのゲートから離れていないエンティティ(プレイヤー・Mob等)」を
     * 集合として持たせている。
     *
     * - touchingThisTick: このサーバーティック中に(いずれかの worldstitch ポータル面に)触れたエンティティ
     * - awaitingExit: 直近でワープ済みで、まだ一度もポータルから離れていないエンティティ
     *   (毎ティック終わりに touchingThisTick に含まれなくなったエンティティはここから除外される
     *    = 「離れた」と判定してクリアする。詳しくは {@link #tickExitTracking()} を参照)
     */
    private static final Set<UUID> touchingThisTick = new HashSet<>();
    private static final Set<UUID> awaitingExit = new HashSet<>();

    /** ServerTickEvents.END_SERVER_TICK から毎ティック呼ぶ。 */
    public static void tickExitTracking() {
        awaitingExit.removeIf(uuid -> !touchingThisTick.contains(uuid));
        touchingThisTick.clear();
    }

    /**
     * カーソル(照準)をリンク済みのポータルに合わせている間、接続先のセーブ名・座標・
     * (設定されていれば)説明文をアクションバーに表示する仕組み。
     *
     * 実装はサーバー側だけで完結させている: 毎ティック全部ではなく
     * {@link #GAZE_HUD_INTERVAL_TICKS} ごとに、各プレイヤーの照準の先を
     * サーバー側で直接レイキャストして判定する。これならクライアント側の
     * 描画コードやブロックエンティティの同期を新設する必要がなく、
     * 見なくなれば(送るのをやめれば)バニラのアクションバー表示が自然に消えていく。
     *
     * ServerTickEvents.END_SERVER_TICK から毎ティック呼ぶ。
     */
    private static int gazeHudTickCounter = 0;
    private static final int GAZE_HUD_INTERVAL_TICKS = 10;

    public static void tickGazeHud(MinecraftServer server) {
        if (gazeHudTickCounter++ % GAZE_HUD_INTERVAL_TICKS != 0) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            HitResult hit = player.pick(6.0, 1.0f, false);
            if (!(hit instanceof BlockHitResult blockHit)) {
                continue;
            }
            Level level = player.level();
            BlockPos pos = blockHit.getBlockPos();
            if (!level.getBlockState(pos).is(WorldstitchMod.GATE_PORTAL_BLOCK)) {
                continue;
            }

            GateFrameBlockEntity anyFrame = findOwningFrame(level, pos);
            if (anyFrame == null) {
                continue;
            }
            // 枠のどの位置がリンク情報を持っていても正しく見つかるよう、繋がっている構造全体から探す。
            GateFrameBlockEntity linkedFrame = PortalAreaHelper.findLinkedFrameInGroup(level, anyFrame.getBlockPos());
            if (linkedFrame == null) {
                continue;
            }

            player.sendOverlayMessage(buildGazeMessage(linkedFrame));
        }
    }

    private static Component buildGazeMessage(GateFrameBlockEntity linkedFrame) {
        BlockPos targetPos = linkedFrame.getTargetPos();
        MutableComponent message = Component.literal(
                "→ " + linkedFrame.getTargetWorldName() + " ("
                        + targetPos.getX() + ", " + targetPos.getY() + ", " + targetPos.getZ() + ")");
        String description = linkedFrame.getLinkDescription();
        if (!description.isBlank()) {
            message = message.append(Component.literal("  『" + description + "』"));
        }
        return message;
    }

    @Override
    protected void entityInside(BlockState state, Level world, BlockPos pos, Entity entity,
                                 InsideBlockEffectApplier effectApplier, boolean bl) {
        super.entityInside(state, world, pos, entity, effectApplier, bl);

        if (world.isClientSide()) {
            return;
        }
        // Mob・アイテムエンティティ等も含めて通過・転移できるようにする(別セーブへの転移だけは
        // プレイヤー専用。PortalTeleporter#beginTravel 側で分岐している)。
        // ただし、まだこの枠にリンクが張られていない・乗り物に乗っている最中の乗客(親側で
        // まとめて転移させたいので子側は個別に処理しない)は対象外にする。
        if (entity.getVehicle() != null) {
            return;
        }

        // 触れている事実は、クールダウン中でも記録しておく(離れたかどうかの判定に使うため)
        touchingThisTick.add(entity.getUUID());

        if (entity.isOnPortalCooldown()) {
            return;
        }
        if (awaitingExit.contains(entity.getUUID())) {
            // まだ前回のワープからこのゲートを離れていないので、再発火させない
            // (ネザーポータルと同じく、一度離れてから触れ直すまでワープしない仕様)
            return;
        }

        GateFrameBlockEntity frame = findOwningFrame(world, pos);
        if (frame == null || !frame.isLinked()) {
            return;
        }

        BlockPos safeExit = computeSafeExit(world, pos, frame);
        PortalTeleporter.beginTravel(entity, frame.getTargetWorldId(), frame.getTargetDimensionKey(),
                frame.getTargetPos(), safeExit);
        awaitingExit.add(entity.getUUID());
    }

    /**
     * 別セーブへの転移が起きた際、元の世界側でプレイヤーを逃がす先を計算する。
     *
     * ポータル面(portalMin〜portalMax)が薄い(=幅0の)軸を「法線方向」とみなし、
     * その軸に沿って2ブロック外側へ出す。両側とも塞がっている場合はとりあえず真上に逃がす。
     */
    private static BlockPos computeSafeExit(Level world, BlockPos portalPos, GateFrameBlockEntity frame) {
        BlockPos min = frame.getPortalMin();
        BlockPos max = frame.getPortalMax();

        Direction.Axis normalAxis;
        if (min != null && max != null && min.getX() == max.getX()) {
            normalAxis = Direction.Axis.X;
        } else if (min != null && max != null && min.getZ() == max.getZ()) {
            normalAxis = Direction.Axis.Z;
        } else {
            // 想定外の形状の場合は、とりあえず真上に逃がす
            return portalPos.above();
        }

        Direction positive = normalAxis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction negative = positive.getOpposite();

        BlockPos positiveSide = portalPos.relative(positive, 2);
        BlockPos negativeSide = portalPos.relative(negative, 2);

        if (isOpen(world, positiveSide)) {
            return positiveSide;
        }
        if (isOpen(world, negativeSide)) {
            return negativeSide;
        }
        return portalPos.above();
    }

    /** 立ってても窒息しない程度に開けている場所かどうかの簡易チェック。 */
    private static boolean isOpen(Level world, BlockPos pos) {
        return !world.getBlockState(pos).isSolid() && !world.getBlockState(pos.above()).isSolid();
    }

    /**
     * このポータル面ブロックが所属するフレームを探す。
     * ポータル面のブロック同士を幅優先で辿っていき、隣接する
     * GateFrameBlockEntity を集めていく。
     *
     * 矩形の枠を構成するブロックのうち、実際にリンク情報を持っているのは
     * リンクコマンドで指定された1箇所だけなので、見つかった枠のうち
     * 「リンク済み」のものを優先して返す(見つからなければ未リンクのものでも返す)。
     */
    private static GateFrameBlockEntity findOwningFrame(Level world, BlockPos start) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        GateFrameBlockEntity fallback = null;

        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4096) {
            BlockPos current = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = current.relative(direction);
                if (!visited.add(neighbor)) {
                    continue;
                }
                BlockEntity blockEntity = world.getBlockEntity(neighbor);
                if (blockEntity instanceof GateFrameBlockEntity frame) {
                    if (frame.isLinked()) {
                        return frame;
                    }
                    if (fallback == null) {
                        fallback = frame;
                    }
                    continue;
                }
                if (world.getBlockState(neighbor).is(WorldstitchMod.GATE_PORTAL_BLOCK)) {
                    queue.add(neighbor);
                }
            }
        }
        return fallback;
    }
}
