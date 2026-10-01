package com.example.worldstitch.util;

import com.example.worldstitch.WorldstitchMod;
import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.config.WorldstitchConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GateFrameBlock で組まれた矩形の枠を検出し、
 * 内側を GatePortalBlock で敷き詰める処理。
 *
 * クリックされた枠ブロックに隣接する「内側」のセル(空気やポータル面)を起点に、
 * バニラのネザーポータルと同じ考え方で内寸を求める(内側だけを下・左方向、
 * 幅方向・高さ方向へ辿って矩形を確定し、外周の枠が揃っているかを確認する)。
 * この方式のため、四隅のブロックは無くてもよい(ネザーポータルと同じ仕様)。
 * クリックした場所が辺の途中でも、角そのものでも検出できる。
 *
 * サイズ制約(既定は内寸 最小2幅x3高さ・最大21x21、ネザーポータルと同じ)は
 * {@link WorldstitchConfig} から読む。
 */
public final class PortalAreaHelper {

    private PortalAreaHelper() {
    }

    public static boolean tryFormPortal(Level world, BlockPos activatedFramePos) {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Rect rect = findRect(world, activatedFramePos, axis);
            if (rect != null) {
                fill(world, rect, axis);
                markFrameEntities(world, rect);
                return true;
            }
        }
        return false;
    }

    private static Rect findRect(Level world, BlockPos start, Direction.Axis axis) {
        if (!isFrame(world, start)) {
            return null;
        }

        Direction widthDir = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;
        Direction widthDirNeg = widthDir.getOpposite();

        // クリックされたのは枠ブロックなので、それに隣接する「内側らしきセル」の候補を集める。
        // 天井側や側面をクリックした場合、外側にも(枠の外の青空のような)空気があるため、
        // 候補が複数見つかることがある。以前は最初に見つかった候補で決め打ちしていたため、
        // 例えば天井の枠を右クリックすると、本来の内側(下方向)より先に上方向の
        // 外の空気を「内側」だと誤認し、そのまま矩形の検証に失敗して
        // 「ポータル面の形成に失敗しました」となってしまっていた。
        // これを防ぐため、候補ごとに実際に矩形が組み立てられるかを試し、
        // 本当に検証まで通った候補が見つかるまで順に試す。
        for (BlockPos seed : candidateInteriorSeeds(world, start, widthDir, widthDirNeg)) {
            Rect rect = buildRectFromSeed(world, seed, axis, widthDir, widthDirNeg);
            if (rect != null) {
                return rect;
            }
        }
        return null;
    }

    /**
     * start(枠ブロック)に隣接する「内側(空気またはポータル面)」らしきセルの候補をすべて集める。
     * まず直交4方向(辺の途中をクリックした場合、本来の内側はここに含まれる)、
     * 続いて斜め4方向(角そのものをクリックした場合、本来の内側はここに含まれる)の順。
     * 外側にも偶然空気があるだけの「はずれ」候補が混ざることがあるが、
     * 呼び出し側({@link #findRect})でそれぞれ実際に検証するため、ここでは絞り込まない。
     */
    private static List<BlockPos> candidateInteriorSeeds(Level world, BlockPos start,
                                                           Direction widthDir, Direction widthDirNeg) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos[] orthogonal = {
                start.relative(widthDir), start.relative(widthDirNeg), start.above(), start.below()
        };
        for (BlockPos candidate : orthogonal) {
            if (isInner(world, candidate)) {
                candidates.add(candidate);
            }
        }
        BlockPos[] diagonal = {
                start.relative(widthDir).above(), start.relative(widthDir).below(),
                start.relative(widthDirNeg).above(), start.relative(widthDirNeg).below()
        };
        for (BlockPos candidate : diagonal) {
            if (isInner(world, candidate)) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    /**
     * 内側らしき1セル(seed)を起点に、実際に矩形を組み立てて検証する。
     * seed が本当に内側でなければ(例えば枠の外の青空だった場合)、
     * 辿った先で外周が枠で揃わず null を返す。
     */
    private static Rect buildRectFromSeed(Level world, BlockPos seed, Direction.Axis axis,
                                           Direction widthDir, Direction widthDirNeg) {
        int minInnerWidth = WorldstitchConfig.get().minGateInnerWidth;
        int minInnerHeight = WorldstitchConfig.get().minGateInnerHeight;
        int maxInnerSize = WorldstitchConfig.get().maxGateInnerSize;

        // 内側(空気・ポータル面)を下・幅マイナス方向へ辿れるだけ辿って、内側の左下を求める
        // (ネザーポータルと同じ考え方: 内側だけを基準に矩形を求めるので、四隅の枠は一切見ない)
        BlockPos bottomLeft = seed;
        int guard = 0;
        while (isInner(world, bottomLeft.below()) && guard++ < maxInnerSize + 2) {
            bottomLeft = bottomLeft.below();
        }
        guard = 0;
        while (isInner(world, bottomLeft.relative(widthDirNeg)) && guard++ < maxInnerSize + 2) {
            bottomLeft = bottomLeft.relative(widthDirNeg);
        }

        // 内側の左下から、幅方向・高さ方向にそれぞれ辿れるだけ辿って内寸を求める
        int innerWidth = 1;
        BlockPos cursor = bottomLeft;
        while (innerWidth <= maxInnerSize && isInner(world, cursor.relative(widthDir))) {
            cursor = cursor.relative(widthDir);
            innerWidth++;
        }
        int innerHeight = 1;
        cursor = bottomLeft;
        while (innerHeight <= maxInnerSize && isInner(world, cursor.above())) {
            cursor = cursor.above();
            innerHeight++;
        }

        if (innerWidth < minInnerWidth || innerWidth > maxInnerSize) {
            return null;
        }
        if (innerHeight < minInnerHeight || innerHeight > maxInnerSize) {
            return null;
        }

        BlockPos innerMin = bottomLeft;
        BlockPos innerMax = bottomLeft.relative(widthDir, innerWidth - 1).above(innerHeight - 1);

        int minA = axis == Direction.Axis.X ? innerMin.getX() : innerMin.getZ();
        int maxA = axis == Direction.Axis.X ? innerMax.getX() : innerMax.getZ();
        int minY = innerMin.getY();
        int maxY = innerMax.getY();
        int fixedOther = axis == Direction.Axis.X ? innerMin.getZ() : innerMin.getX();

        // 外周(四隅を除く)がすべて枠で、内側がすべて空気/ポータル面であることを確認する。
        // ネザーポータルと同じく、四隅のブロックは存在してもしなくてもよいので検査自体をしない。
        for (int a = minA - 1; a <= maxA + 1; a++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                boolean isCorner = (a == minA - 1 || a == maxA + 1) && (y == minY - 1 || y == maxY + 1);
                if (isCorner) {
                    continue;
                }
                BlockPos pos = toPos(axis, a, y, fixedOther);
                boolean isBorder = (a == minA - 1 || a == maxA + 1 || y == minY - 1 || y == maxY + 1);
                if (isBorder) {
                    if (!isFrame(world, pos)) {
                        return null;
                    }
                } else {
                    if (!isInner(world, pos)) {
                        return null;
                    }
                }
            }
        }

        return new Rect(innerMin, innerMax);
    }

    private static BlockPos toPos(Direction.Axis axis, int a, int y, int other) {
        return axis == Direction.Axis.X ? new BlockPos(a, y, other) : new BlockPos(other, y, a);
    }

    /**
     * 与えられた枠ブロックの座標から、そのゲート構造の「着地点」
     * (枠の最下段=床のすぐ上、かつ開口部の幅方向の中央)を求める。
     * リンクにどの枠ブロック(角でも辺の途中でも)が使われていても同じ結果になるようにするための処理。
     * ゲート構造が見つからない場合は、渡された座標をそのまま返す(フォールバック)。
     */
    public static BlockPos computeLandingSpot(Level world, BlockPos anyFramePos) {
        if (!isFrame(world, anyFramePos)) {
            return anyFramePos;
        }
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Rect rect = findRect(world, anyFramePos, axis);
            if (rect != null) {
                return landingFromRect(rect);
            }
        }
        return anyFramePos;
    }

    private static BlockPos landingFromRect(Rect rect) {
        BlockPos min = rect.innerMin();
        BlockPos max = rect.innerMax();
        // 内側の最下段(= 枠の床のすぐ上)がプレイヤーの立ち位置。
        // Entity の座標系ではYは足元の高さを表すため、この段の座標をそのまま使えば
        // 下枠のブロックにちょうど足が接地した状態で出てくる。
        int landingY = min.getY();
        if (min.getX() != max.getX()) {
            int centerX = Math.floorDiv(min.getX() + max.getX(), 2);
            return new BlockPos(centerX, landingY, min.getZ());
        }
        int centerZ = Math.floorDiv(min.getZ() + max.getZ(), 2);
        return new BlockPos(min.getX(), landingY, centerZ);
    }

    private static boolean isFrame(Level world, BlockPos pos) {
        return world.getBlockState(pos).is(WorldstitchMod.GATE_FRAME_BLOCK);
    }

    private static boolean isPortal(Level world, BlockPos pos) {
        return world.getBlockState(pos).is(WorldstitchMod.GATE_PORTAL_BLOCK);
    }

    private static boolean isInner(Level world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || state.is(WorldstitchMod.GATE_PORTAL_BLOCK);
    }

    /**
     * 枠ブロックが1つ破壊された直後に呼ぶ。
     * 壊れた位置につながっている残りの枠・ポータル面をすべて辿って
     * ポータル面を空気に戻し、残った枠のリンク情報もクリアする
     * (枠ブロック自体はここでは壊さない。既に壊れた1マス分は呼び出し側の通常の破壊処理に任せる)。
     *
     * 後始末を辿るのは「実際に形成されているポータル面」を介した経路だけに限定している
     * (壊れた位置の隣にポータル面が無ければ、その先へは一切辿らない)。
     * こうしないと、矩形の検出には使われない四隅などに置いた装飾目的の無関係な枠ブロックが
     * 本物の枠ブロックにただ接しているだけで、それを壊した時に本物のポータルまで
     * 巻き込んで解除されてしまう(角は本来ネザーポータルと同様に無くてもよい飾りであり、
     * ポータル面には直交方向で隣接しないため、この制限だけで正しく区別できる)。
     *
     * 隣接するゲート同士が枠を1列共有している場合(例: 行き先の違う2つのゲートが
     * 真ん中の枠列を共有して並んでいる)、共有列のどれか1つを壊すと、その位置は
     * 両方のポータル面に同時に隣接することになる。もしこれを1回のフラッドとして
     * まとめて処理してしまうと、無関係な相手側ゲートまで巻き込んで解除されてしまう上に、
     * 「相手側への通知」は1件分しか持てない作りだったため、巻き込まれた側の相手には
     * 通知が届かず、リンクが崩れたまま残ってしまう不具合があった。
     *
     * これを避けるため、壊れた位置から出る方向ごとに完全に独立したフラッドとして
     * 後始末を行う(ただし同じポータル面へ複数方向からたどり着く場合は visited を
     * 共有しているので、自然に1回のフラッドにまとまる=同じゲートとして正しく扱われる)。
     *
     * @return このゲートが持っていた、および巻き込まれた各方向で見つかったリンク情報の一覧
     *         (相手側の後始末に使う)。何もリンクされていなければ空リスト。
     */
    public static List<GateLinkInfo> tearDownAfterFrameBreak(Level world, BlockPos brokenPos, GateFrameBlockEntity brokenFrame) {
        List<GateLinkInfo> linkInfos = new ArrayList<>();
        if (brokenFrame.isLinked()) {
            linkInfos.add(new GateLinkInfo(brokenFrame.getTargetWorldId(), brokenFrame.getTargetDimensionKey(),
                    brokenFrame.getTargetPos()));
        }

        Set<BlockPos> visited = new HashSet<>();
        visited.add(brokenPos);

        for (Direction direction : Direction.values()) {
            BlockPos seed = brokenPos.relative(direction);
            if (visited.contains(seed) || !isPortal(world, seed)) {
                continue;
            }

            Deque<BlockPos> queue = new ArrayDeque<>();
            visited.add(seed);
            queue.add(seed);

            GateLinkInfo[] linkInfoHolder = new GateLinkInfo[1];
            floodClearRemaining(world, visited, queue, linkInfoHolder);
            if (linkInfoHolder[0] != null) {
                linkInfos.add(linkInfoHolder[0]);
            }
        }

        return linkInfos;
    }

    /**
     * まだ壊れていない枠(framePos)を起点に、つながっているポータル面・枠をすべて後始末する。
     * 枠ブロック自体は残したまま、中身(ポータル面)を消してリンク情報だけクリアする。
     * 別セーブへ伝播したリンク解除を適用する時や、同一ワールド内リンクの相手側を
     * 直接処理する時に使う。
     *
     * この枠自身のリンクは無条件で消すが、そこから先へ辿るのは
     * {@link #tearDownAfterFrameBreak} と同じく実際のポータル面を介した経路だけに限る。
     */
    public static void tearDownGateAt(Level world, BlockPos framePos) {
        BlockEntity anchorEntity = world.getBlockEntity(framePos);
        if (!(anchorEntity instanceof GateFrameBlockEntity anchorFrame)) {
            return;
        }
        anchorFrame.clearLink();
        anchorFrame.clearPortalArea();

        Set<BlockPos> visited = new HashSet<>();
        visited.add(framePos);
        Deque<BlockPos> queue = new ArrayDeque<>();
        for (Direction direction : Direction.values()) {
            BlockPos next = framePos.relative(direction);
            if (visited.add(next) && isPortal(world, next)) {
                queue.add(next);
            }
        }

        floodClearRemaining(world, visited, queue, new GateLinkInfo[1]);
    }

    /**
     * queue に積まれたポータル面のセルを起点に辿って、ポータル面を消し枠のリンクをクリアする共通処理。
     *
     * 起点となる queue の中身は必ずポータル面のセルのみ(呼び出し側でそう制限している)。
     * ポータル面のセルからは隣接する枠・ポータル面のどちらへも辿るが、
     * 枠のセルに着いたらそこで探索を止める(枠→枠へは伝播しない)。
     * これにより、本物の矩形の外周に接しているだけで実際にはポータル面に一切触れていない
     * 無関係な枠ブロック(装飾用の角など)へ後始末が波及することはない。
     */
    private static void floodClearRemaining(Level world, Set<BlockPos> visited, Deque<BlockPos> queue,
                                             GateLinkInfo[] linkInfoHolder) {
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 8192) {
            BlockPos current = queue.poll();
            boolean currentIsPortal = isPortal(world, current);
            if (currentIsPortal) {
                world.removeBlock(current, false);
            } else if (isFrame(world, current)) {
                BlockEntity blockEntity = world.getBlockEntity(current);
                if (blockEntity instanceof GateFrameBlockEntity frame) {
                    if (linkInfoHolder[0] == null && frame.isLinked()) {
                        linkInfoHolder[0] = new GateLinkInfo(frame.getTargetWorldId(), frame.getTargetDimensionKey(),
                                frame.getTargetPos());
                    }
                    frame.clearLink();
                    frame.clearPortalArea();
                }
            }

            if (!currentIsPortal) {
                // 枠のセルからはこれ以上先へ辿らない(枠→枠の伝播を禁止)。
                continue;
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (visited.add(next) && (isFrame(world, next) || isPortal(world, next))) {
                    queue.add(next);
                }
            }
        }
    }

    /**
     * framePos を含む、実際に形成されているポータル面でつながっている枠・ポータル面のかたまり
     * (1つのゲート構造)の中から、実際にリンク情報を持っている枠ブロックエンティティを探す。
     *
     * 1つの矩形の枠は複数の GateFrameBlock からなるが、リンク情報({@link GateFrameBlockEntity#setLink})
     * は /worldstitch mark・link を実行した時に見ていた「1つの枠ブロック」だけが持っている。
     * そのため、枠のどこを右クリックしても目印帳の画面やコマンドでリンク解除ができるように、
     * まず物理的に繋がっている構造全体を辿って、実際にリンク情報を持つブロックを見つける
     * (後始末そのものは何もしない、探すだけの読み取り専用処理)。
     *
     * {@link #tearDownAfterFrameBreak} と同じく、実際のポータル面を介した経路だけを辿るため、
     * ポータル面に一切触れていない無関係な枠ブロック(装飾用の角など)を経由して
     * 別の(本来無関係な)ゲート構造まで誤って辿ってしまうことはない。
     *
     * @return 見つかればそのブロックエンティティ。framePos が枠でない、またはつながっている
     *         範囲のどこもリンクされていなければ null。
     */
    public static GateFrameBlockEntity findLinkedFrameInGroup(Level world, BlockPos framePos) {
        return findFrameInGroupMatching(world, framePos, GateFrameBlockEntity::isLinked);
    }

    /**
     * framePos が属するゲート構造(実際に形成されているポータル面を介してつながっている範囲)の中に、
     * 破壊保護が設定されている枠が1つでもあれば、その枠ブロックエンティティを返す。
     * 見つからなければ null(未形成の単体の枠の場合は、framePos 自身だけを見る)。
     */
    public static GateFrameBlockEntity findBreakProtectedFrameInGroup(Level world, BlockPos framePos) {
        return findFrameInGroupMatching(world, framePos, GateFrameBlockEntity::isBreakProtected);
    }

    /** framePos が属するゲート構造のどこかが破壊保護されていれば true。 */
    public static boolean isGroupBreakProtected(Level world, BlockPos framePos) {
        return findBreakProtectedFrameInGroup(world, framePos) != null;
    }

    /**
     * framePos が属するゲート構造(実際に形成されているポータル面を介してつながっている
     * 枠ブロック全部)に対して、破壊保護フラグをまとめて設定する。
     *
     * 枠ブロックが1つでも破壊されると構造全体が巻き込まれて解除される(GateFrameBlock参照)のと
     * 同様に、破壊保護も外周を構成するどの枠ブロックにも波及させておかないと、保護されていない
     * 別の枠ブロックを壊されて結局ゲートごと壊されてしまう。そのため、リンク情報とは異なり
     * 1箇所だけに持たせず、構造に含まれる全ての枠ブロックエンティティへ複製して持たせる。
     *
     * 未形成(ポータル面がまだ無い)の単体の枠に対して呼んだ場合は、framePos 自身にのみ設定する。
     */
    public static void setBreakProtectedForGroup(Level world, BlockPos framePos, boolean protect) {
        if (!(world.getBlockEntity(framePos) instanceof GateFrameBlockEntity anchorFrame)) {
            return;
        }
        anchorFrame.setBreakProtected(protect);

        // まず矩形そのものを求めて外周を正確に走査する方式(#collectFullFrameGroup)を試す。
        // クリックした枠ブロックが四隅(角)の位置だと、ポータル面に直交方向で一切接していないため、
        // 従来のBFS(ポータル面を辿る方式)では探索の起点を見失い、この1ブロックしか
        // 保護状態が変わらないという不具合があった。矩形ベースの方式なら起点が角でも辺の
        // 途中でも同じ構造全体を正しく取得できる。
        List<BlockPos> group = collectFullFrameGroup(world, framePos);
        if (!group.isEmpty()) {
            for (BlockPos pos : group) {
                if (world.getBlockEntity(pos) instanceof GateFrameBlockEntity frame) {
                    frame.setBreakProtected(protect);
                }
            }
            return;
        }

        // 矩形として検出できない場合(壊れている・未形成等)のフォールバック。
        // 以前からのポータル面を辿るBFS方式で、可能な範囲だけ伝播させる。
        Set<BlockPos> visited = new HashSet<>();
        visited.add(framePos);
        Deque<BlockPos> queue = new ArrayDeque<>();
        for (Direction direction : Direction.values()) {
            BlockPos next = framePos.relative(direction);
            if (visited.add(next) && isPortal(world, next)) {
                queue.add(next);
            }
        }

        int guard = 0;
        while (!queue.isEmpty() && guard++ < 8192) {
            BlockPos current = queue.poll();
            boolean currentIsPortal = isPortal(world, current);
            if (!currentIsPortal && isFrame(world, current)) {
                if (world.getBlockEntity(current) instanceof GateFrameBlockEntity frame) {
                    frame.setBreakProtected(protect);
                }
            }

            if (!currentIsPortal) {
                // 枠のセルからはこれ以上先へ辿らない(枠→枠の伝播を禁止)。
                continue;
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (visited.add(next) && (isFrame(world, next) || isPortal(world, next))) {
                    queue.add(next);
                }
            }
        }
    }

    /**
     * framePos を含む「実際の1つの矩形ゲート構造」を成す、外周の枠ブロックの座標をすべて集める
     * ({@link #computeLandingSpot} と同じ考え方: まず {@link #findRect} で実際の矩形そのものを、
     * どの枠ブロックを起点にしても―角でも辺の途中でも―同じ結果になるように求め、
     * その矩形の外周(四隅を除く)を実際に走査して枠ブロックを集める)。
     *
     * ポータル面を辿るBFS方式と違い、起点がたまたま四隅の位置であっても、
     * あるいはポータル面がまだ形成されていなくても(内側が単なる空気でも)正しく機能する。
     *
     * @return 見つかった外周の枠ブロックの座標(framePos 自身は含まない)。矩形が見つからない
     *         (=枠の形が崩れている等)場合は空リスト。
     */
    private static List<BlockPos> collectFullFrameGroup(Level world, BlockPos framePos) {
        for (Direction.Axis axis : new Direction.Axis[]{Direction.Axis.X, Direction.Axis.Z}) {
            Rect rect = findRect(world, framePos, axis);
            if (rect != null) {
                return borderFramePositions(world, rect, axis, framePos);
            }
        }
        return List.of();
    }

    /** rect の外周(四隅を除く)のうち、実際に枠ブロックであるものの座標を集める(exclude自身は除く)。 */
    private static List<BlockPos> borderFramePositions(Level world, Rect rect, Direction.Axis axis, BlockPos exclude) {
        List<BlockPos> result = new ArrayList<>();
        BlockPos innerMin = rect.innerMin();
        BlockPos innerMax = rect.innerMax();
        int minA = axis == Direction.Axis.X ? innerMin.getX() : innerMin.getZ();
        int maxA = axis == Direction.Axis.X ? innerMax.getX() : innerMax.getZ();
        int minY = innerMin.getY();
        int maxY = innerMax.getY();
        int fixedOther = axis == Direction.Axis.X ? innerMin.getZ() : innerMin.getX();

        for (int a = minA - 1; a <= maxA + 1; a++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                boolean isCorner = (a == minA - 1 || a == maxA + 1) && (y == minY - 1 || y == maxY + 1);
                if (isCorner) {
                    continue;
                }
                boolean isBorder = (a == minA - 1 || a == maxA + 1 || y == minY - 1 || y == maxY + 1);
                if (!isBorder) {
                    continue;
                }
                BlockPos pos = toPos(axis, a, y, fixedOther);
                if (!pos.equals(exclude) && isFrame(world, pos)) {
                    result.add(pos);
                }
            }
        }
        return result;
    }

    /**
     * framePos が属するゲート構造(実際に形成されているポータル面を介してつながっている範囲)の中から、
     * predicate に最初に一致した枠ブロックエンティティを探す(読み取り専用の共通探索処理)。
     * {@link #findLinkedFrameInGroup}・{@link #findBreakProtectedFrameInGroup} の実体。
     */
    private static GateFrameBlockEntity findFrameInGroupMatching(Level world, BlockPos framePos,
            java.util.function.Predicate<GateFrameBlockEntity> predicate) {
        if (!(world.getBlockEntity(framePos) instanceof GateFrameBlockEntity startFrame)) {
            return null;
        }
        if (predicate.test(startFrame)) {
            return startFrame;
        }

        // まず矩形ベースの方式(#collectFullFrameGroup、詳細は setBreakProtectedForGroup 側の
        // コメント参照)を試す。起点が四隅の位置でも正しく構造全体を取得できる。
        List<BlockPos> group = collectFullFrameGroup(world, framePos);
        if (!group.isEmpty()) {
            for (BlockPos pos : group) {
                if (world.getBlockEntity(pos) instanceof GateFrameBlockEntity frame && predicate.test(frame)) {
                    return frame;
                }
            }
            return null;
        }

        // 矩形として検出できない場合(壊れている・未形成等)のフォールバック。
        // 以前からのポータル面を辿るBFS方式で、可能な範囲だけ探す。
        Set<BlockPos> visited = new HashSet<>();
        visited.add(framePos);
        Deque<BlockPos> queue = new ArrayDeque<>();
        for (Direction direction : Direction.values()) {
            BlockPos next = framePos.relative(direction);
            if (visited.add(next) && isPortal(world, next)) {
                queue.add(next);
            }
        }

        int guard = 0;
        while (!queue.isEmpty() && guard++ < 8192) {
            BlockPos current = queue.poll();
            boolean currentIsPortal = isPortal(world, current);
            if (!currentIsPortal && isFrame(world, current)) {
                BlockEntity blockEntity = world.getBlockEntity(current);
                if (blockEntity instanceof GateFrameBlockEntity frame && predicate.test(frame)) {
                    return frame;
                }
            }

            if (!currentIsPortal) {
                // 枠のセルからはこれ以上先へ辿らない(枠→枠の伝播を禁止)。
                continue;
            }

            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (visited.add(next) && (isFrame(world, next) || isPortal(world, next))) {
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** 破壊された枠が持っていたリンク先の情報。 */
    public record GateLinkInfo(String worldId, ResourceKey<Level> dimension, BlockPos pos) {
    }

    private static void fill(Level world, Rect rect, Direction.Axis axis) {
        BlockState portalState = WorldstitchMod.GATE_PORTAL_BLOCK.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_AXIS, axis);
        for (int x = rect.innerMin().getX(); x <= rect.innerMax().getX(); x++) {
            for (int y = rect.innerMin().getY(); y <= rect.innerMax().getY(); y++) {
                for (int z = rect.innerMin().getZ(); z <= rect.innerMax().getZ(); z++) {
                    world.setBlockAndUpdate(new BlockPos(x, y, z), portalState);
                }
            }
        }
    }

    /** 枠のどこかにポータル範囲を記録しておく(GatePortalBlock側の探索フォールバック用)。 */
    private static void markFrameEntities(Level world, Rect rect) {
        for (Direction direction : Direction.values()) {
            BlockEntity a = world.getBlockEntity(rect.innerMin().relative(direction));
            if (a instanceof GateFrameBlockEntity frame) {
                frame.setPortalArea(rect.innerMin(), rect.innerMax());
            }
            BlockEntity b = world.getBlockEntity(rect.innerMax().relative(direction));
            if (b instanceof GateFrameBlockEntity frame) {
                frame.setPortalArea(rect.innerMin(), rect.innerMax());
            }
        }
    }

    private record Rect(BlockPos innerMin, BlockPos innerMax) {
    }
}
