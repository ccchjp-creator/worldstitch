package com.example.worldstitch;

import com.example.worldstitch.block.GateFrameBlock;
import com.example.worldstitch.block.GatePortalBlock;
import com.example.worldstitch.block.entity.GateFrameBlockEntity;
import com.example.worldstitch.command.WorldstitchCommands;
import com.example.worldstitch.config.WorldstitchConfig;
import com.example.worldstitch.item.GateLinkerItem;
import com.example.worldstitch.network.OpenGateLinkScreenPayload;
import com.example.worldstitch.network.SetGateDescriptionPayload;
import com.example.worldstitch.network.SubmitGateLinkPayload;
import com.example.worldstitch.network.ToggleFrameBreakProtectionPayload;
import com.example.worldstitch.network.UnlinkGateFramePayload;
import com.example.worldstitch.util.ClientWorldSwitchBridge;
import com.example.worldstitch.util.BonusChestLoot;
import com.example.worldstitch.util.GateLinkOperations;
import com.example.worldstitch.util.PendingFrameLink;
import com.example.worldstitch.util.PendingFrameProtection;
import com.example.worldstitch.util.PendingFrameUnlink;
import com.example.worldstitch.util.PendingPortalRecolor;
import com.example.worldstitch.util.PendingLandingFix;
import com.example.worldstitch.util.PendingTransfer;
import com.example.worldstitch.util.PortalAreaHelper;
import com.example.worldstitch.util.WorldResidency;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Function;

/**
 * ワールド間移動MOD(プロトタイプ)のメイン初期化クラス。
 *
 * 26.2 では BlockBehaviour.Properties に setId(ResourceKey) で
 * あらかじめ登録キーを渡す形に変わっているため、その流儀に合わせている。
 * (参考: docs.fabricmc.net の「はじめてのブロック」26.1.2版のサンプルコード)
 */
public class WorldstitchMod implements ModInitializer {

    /** リソースの名前空間。ハイフンはMOD IDとして使えないため使用しない。 */
    public static final String MOD_ID = "worldstitch";

    public static final Block GATE_FRAME_BLOCK = registerBlock(
            "gate_frame_block",
            GateFrameBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .requiresCorrectToolForDrops()
                    .strength(4.0f, 1200.0f),
            true
    );

    public static final Block GATE_PORTAL_BLOCK = registerBlock(
            "gate_portal_block",
            GatePortalBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_CYAN)
                    .noCollision()
                    .noOcclusion()
                    .noLootTable()
                    .lightLevel(state -> 11)
                    // バニラのネザーポータル(strength(-1.0F))と同じく、硬度・爆発耐性ともに
                    // 負の値にして「一切採掘できない」状態にする。破壊保護のON/OFFとは無関係に、
                    // ポータル面自体はプレイヤーが直接壊せない(枠を壊した時のプログラム的な
                    // 撤去処理 PortalAreaHelper#tearDownGateAt 等はブロックを直接除去するので、
                    // これによる影響は受けない)。
                    .strength(-1.0f),
            false
    );

    public static final BlockEntityType<GateFrameBlockEntity> GATE_FRAME_BLOCK_ENTITY = Registry.register(
            BuiltInRegistries.BLOCK_ENTITY_TYPE,
            id("gate_frame_block_entity"),
            FabricBlockEntityTypeBuilder.create(GateFrameBlockEntity::new, GATE_FRAME_BLOCK).build()
    );

    public static final Item GATE_LINKER_ITEM = registerItem(
            "gate_linker",
            GateLinkerItem::new,
            new Item.Properties()
    );

    /**
     * クリエイティブインベントリ用の、このMOD専用タブ。
     * 実際に取得できるアイテム(ゲートフレームブロック・ゲートの目印帳)だけを並べる。
     * gate_portal_block はアイテム自体が存在しない(手動設置不可)ため、ここには出てこない。
     */
    public static final CreativeModeTab ITEM_GROUP = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            id("main"),
            FabricCreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.worldstitch.main"))
                    .icon(() -> new ItemStack(GATE_FRAME_BLOCK))
                    .displayItems((parameters, output) -> {
                        output.accept(GATE_FRAME_BLOCK);
                        output.accept(GATE_LINKER_ITEM);
                    })
                    .build()
    );

    @Override
    public void onInitialize() {
        // 各フィールドの static 初期化により、登録処理はクラス読み込み時に完了している。

        // config/worldstitch.json を読み込む(無ければデフォルト値で新規作成する)。
        WorldstitchConfig.load();

        // ワールド新規作成時の「ボーナスチェスト」に、ゲートフレーム16個・目印帳1個を確定で追加する。
        BonusChestLoot.register();

        // このセーブがロードされ、統合サーバーが起動した直後に、
        // 別セーブから転移してきたプレイヤーデータ(あれば)を適用する。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean arrivedViaPortal = PendingTransfer.applyPendingTransfers(server);
            if (arrivedViaPortal) {
                // 正規のポータル転移でここへ戻ってきたので、離脱記録が残っていれば消しておく
                WorldResidency.clearDeparted(server);
            }
            // このセーブ宛てのリンク解除予約が残っていれば、対象の枠のチャンクが
            // 読み込まれるのを待たずに離脱マーカーだけ先にクリアしておく
            // (詳細は PendingFrameUnlink#clearDepartedMarkerIfAnyPending 参照)。
            PendingFrameUnlink.clearDepartedMarkerIfAnyPending(server);
        });

        // 「このセーブ自身が既に別セーブへ離脱済み」と記録されている場合、
        // (=ワールド一覧から直接この古いセーブを開いてしまった場合)
        // 実際に居るべきセーブへ自動的に案内する。
        //
        // これは SERVER_STARTED ではなく、プレイヤーが実際にログイン完了した瞬間(JOIN)に行う。
        // SERVER_STARTED の時点ではまだクライアント側の「ワールドを開く」処理
        // (WorldOpenFlows の一連の非同期処理)が完了しきっていないことがあり、
        // そのタイミングで横からもう一度ワールド切り替えを割り込ませると
        // クライアント内部の状態が壊れてクラッシュする(実際に確認済み)。
        // JOIN まで待てば、その一連の処理は必ず完了しているので安全。
        //
        // 離脱記録は「このセーブ自身」の中に持たせているので、無関係な新規ワールドを
        // 作成・オープンしただけでは絶対に誤反応しない(記録が無いだけなので)。
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            String departedTo = WorldResidency.getDepartedTo(server);
            if (departedTo != null) {
                if (!WorldstitchConfig.get().allowEnteringDepartedSave) {
                    ClientWorldSwitchBridge.requestSwitch(departedTo);
                    return;
                }
                // 設定(allowEnteringDepartedSave)で直接プレイが許可されているので、
                // 案内はせずそのまま続ける(離脱記録自体は /worldstitch residency clear まで残す)。
                listener.player.sendSystemMessage(Component.translatable(
                        "worldstitch.message.departed_but_allowed", departedTo));
            }
            // 別セーブからの正規のポータル転移で今このセーブに来た場合、
            // 着地点をゲート構造(床の上・開口部中央)に補正する予約があれば適用する。
            PendingLandingFix.applyIfPending(listener.player);
        });

        // ゲートフレームが読み込まれた時、自分宛ての「戻りリンク」予約があれば適用する(双方向リンク)。
        // ※ BLOCK_ENTITY_LOAD はサーバーが完全に立ち上がる前(スポーン地点のチャンク準備中)にも
        //   発火することがあり、その場でポータル面のブロックを書き換えるとチャンク読み込み処理と
        //   競合してサーバーがフリーズする恐れがある。そのためここではキューに積むだけにし、
        //   実際の適用はサーバーが通常のティックに入ってから(END_SERVER_TICK)行う。
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof GateFrameBlockEntity frame) {
                PendingFrameLink.queueApply(level, frame);
                PendingFrameUnlink.queueApply(level, frame);
                PendingPortalRecolor.queueApply(level, frame);
                PendingFrameProtection.queueApply(level, frame);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(PendingFrameLink::runDeferredTasks);
        ServerTickEvents.END_SERVER_TICK.register(PendingFrameUnlink::runDeferredTasks);
        ServerTickEvents.END_SERVER_TICK.register(PendingPortalRecolor::runDeferredTasks);
        ServerTickEvents.END_SERVER_TICK.register(PendingFrameProtection::runDeferredTasks);
        // ポータルに触れたまま留まっているプレイヤーが、クールダウン明けに再度ワープしてしまわないよう、
        // 「ちゃんと一度離れたかどうか」を毎ティック確認する(詳細は GatePortalBlock 側のコメント参照)。
        ServerTickEvents.END_SERVER_TICK.register(server -> GatePortalBlock.tickExitTracking());
        // カーソルをリンク済みのポータルに合わせている間、接続先のセーブ名・座標・説明文を
        // アクションバーに表示する(詳細は GatePortalBlock#tickGazeHud のコメント参照)。
        ServerTickEvents.END_SERVER_TICK.register(GatePortalBlock::tickGazeHud);

        // 破壊保護が設定されているゲート枠を、殴った瞬間(採掘バーが1ミリも進む前)に弾く。
        // 硬度(destroyTime)自体は同じブロックである以上どのインスタンスでも共通の静的な値であり、
        // ブロックステートだけで動的に変える一般的な公開APIが無いため、代わりに「採掘の開始」
        // そのものをキャンセルする方式で、岩盤のように殴っても一切反応しない見た目にしている。
        // (サバイバルでの徐々に削る採掘・クリエイティブでの一撃破壊のどちらもこの1箇所で防げる)
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            BlockState state = level.getBlockState(pos);
            if (!state.is(GATE_FRAME_BLOCK)) {
                return InteractionResult.PASS;
            }
            if (!WorldstitchConfig.get().frameBreakProtectionEnabled) {
                return InteractionResult.PASS;
            }
            // ブロックステートのPROTECTEDはバニラの通常のブロック更新でクライアントにも
            // 同期されているので、クライアント側のこの判定も正しい値で動く
            // (=クライアント自身が採掘バーを1ミリも進めずにその場でキャンセルできる)。
            if (!state.getValue(GateFrameBlock.PROTECTED)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide()) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.break_protected"));
            }
            return InteractionResult.FAIL;
        });

        // 上のAttackBlockCallbackが主だが、万一(コマンド経由の即時破壊など)採掘開始そのものを
        // 経由しない壊し方をされた場合に備えて、実際の破壊が確定する瞬間もあわせて弾いておく。
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (level.isClientSide()) {
                return true;
            }
            if (!state.is(GATE_FRAME_BLOCK)) {
                return true;
            }
            if (!WorldstitchConfig.get().frameBreakProtectionEnabled) {
                return true;
            }
            if (!PortalAreaHelper.isGroupBreakProtected(level, pos)) {
                return true;
            }
            player.sendSystemMessage(Component.translatable("worldstitch.message.break_protected"));
            return false;
        });

        // /worldstitch mark, /worldstitch link コマンドを登録する。
        WorldstitchCommands.register();

        // GateLinkerItem 用の通信(画面を開く/貼り付けたリンクを受け取る)を登録する。
        PayloadTypeRegistry.clientboundPlay().register(OpenGateLinkScreenPayload.TYPE, OpenGateLinkScreenPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SubmitGateLinkPayload.TYPE, SubmitGateLinkPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UnlinkGateFramePayload.TYPE, UnlinkGateFramePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetGateDescriptionPayload.TYPE, SetGateDescriptionPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                ToggleFrameBreakProtectionPayload.TYPE, ToggleFrameBreakProtectionPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(SetGateDescriptionPayload.TYPE, (payload, context) -> {
            var player = context.player();
            BlockEntity blockEntity = player.level().getBlockEntity(payload.framePos());
            if (!(blockEntity instanceof GateFrameBlockEntity frame)) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.frame_not_found"));
                return;
            }

            // 枠のどのブロックから開いた画面でも同じ場所に保存されるよう、繋がっている構造全体から
            // 実際にリンク情報を持つ枠を探す(見つからなければ、クリックした枠自身に保存する)。
            GateFrameBlockEntity target = PortalAreaHelper.findLinkedFrameInGroup(player.level(), frame.getBlockPos());
            if (target == null) {
                target = frame;
            }
            target.setLinkDescription(payload.description());

            if (payload.description().isBlank()) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.description_cleared"));
            } else {
                player.sendSystemMessage(Component.translatable("worldstitch.message.description_set"));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(UnlinkGateFramePayload.TYPE, (payload, context) -> {
            var player = context.player();
            if (!(player.level().getBlockEntity(payload.framePos()) instanceof GateFrameBlockEntity)) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.frame_not_found"));
                return;
            }

            // 枠のどのブロックから開いた画面でも解除できるよう、繋がっている構造全体から探す。
            GateLinkOperations.UnlinkResult result =
                    GateLinkOperations.unlinkFrameGroup(player.level(), payload.framePos());
            if (result == GateLinkOperations.UnlinkResult.UNLINKED) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.unlinked"));
            } else if (result == GateLinkOperations.UnlinkResult.BREAK_PROTECTED) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.unlink_break_protected"));
            } else {
                player.sendSystemMessage(Component.translatable("worldstitch.message.not_linked"));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SubmitGateLinkPayload.TYPE, (payload, context) -> {
            var player = context.player();
            BlockEntity blockEntity = player.level().getBlockEntity(payload.framePos());
            if (!(blockEntity instanceof GateFrameBlockEntity frame)) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.source_frame_not_found"));
                return;
            }

            GateLinkOperations.ParsedMark parsedMark = GateLinkOperations.parseMark(payload.pastedMark());
            if (parsedMark == null) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.invalid_mark"));
                return;
            }

            GateLinkOperations.LinkResult result = GateLinkOperations.applyLink(player, frame, parsedMark);
            if (result == GateLinkOperations.LinkResult.LINKED) {
                player.sendSystemMessage(Component.translatable(
                        "worldstitch.message.linked", parsedMark.worldId(), parsedMark.pos().toShortString()));
            } else if (result == GateLinkOperations.LinkResult.CROSS_DIMENSION_DISABLED) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.cross_dimension_disabled"));
            } else if (result == GateLinkOperations.LinkResult.ALREADY_LINKED) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.already_linked"));
            } else {
                player.sendSystemMessage(Component.translatable("worldstitch.message.portal_form_failed"));
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(ToggleFrameBreakProtectionPayload.TYPE, (payload, context) -> {
            var player = context.player();
            var level = player.level();
            if (!(level.getBlockEntity(payload.framePos()) instanceof GateFrameBlockEntity)) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.frame_not_found"));
                return;
            }

            boolean currentlyProtected = PortalAreaHelper.isGroupBreakProtected(level, payload.framePos());
            GateLinkOperations.applyBreakProtectionToGroup(level, payload.framePos(), !currentlyProtected);

            if (!currentlyProtected) {
                player.sendSystemMessage(Component.translatable("worldstitch.message.break_protection_enabled"));
            } else {
                player.sendSystemMessage(Component.translatable("worldstitch.message.break_protection_disabled"));
            }
        });
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static Block registerBlock(String name, Function<BlockBehaviour.Properties, Block> factory,
                                        BlockBehaviour.Properties properties, boolean registerItem) {
        ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id(name));
        Block block = factory.apply(properties.setId(blockKey));

        if (registerItem) {
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(name));
            BlockItem blockItem = new BlockItem(block, new Item.Properties()
                    .setId(itemKey)
                    .useBlockDescriptionPrefix());
            Registry.register(BuiltInRegistries.ITEM, itemKey, blockItem);
        }

        return Registry.register(BuiltInRegistries.BLOCK, blockKey, block);
    }

    private static Item registerItem(String name, Function<Item.Properties, Item> factory, Item.Properties properties) {
        ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id(name));
        Item item = factory.apply(properties.setId(itemKey));
        return Registry.register(BuiltInRegistries.ITEM, itemKey, item);
    }
}
