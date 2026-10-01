package com.example.worldstitch.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;

import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableSource;

import com.example.worldstitch.WorldstitchMod;

/**
 * ワールド新規作成時に「ボーナスチェスト」設定をONにした場合、そのボーナスチェストの中に
 * ゲートフレーム16個・ゲートの目印帳1個を確定で(抽選なしで必ず)追加する。
 *
 * バニラのボーナスチェストの元々の中身(道具・食料など)には一切手を加えず、
 * それとは別のロットプールとして追加するだけなので、既存のバランスには影響しない。
 * また、データパック等でボーナスチェストのロットテーブル自体が丸ごと置き換えられている
 * 場合は、そちらの内容を尊重してこのMODからは何も追加しない({@link LootTableSource#isBuiltin()}
 * が false になるケース)。
 */
public final class BonusChestLoot {

    /** バニラのボーナスチェストのロットテーブルID(minecraft:chests/spawn_bonus_chest)。 */
    private static final ResourceKey<LootTable> SPAWN_BONUS_CHEST = ResourceKey.create(
            Registries.LOOT_TABLE, Identifier.fromNamespaceAndPath("minecraft", "chests/spawn_bonus_chest"));

    private BonusChestLoot() {
    }

    public static void register() {
        LootTableEvents.MODIFY.register(BonusChestLoot::modify);
    }

    private static void modify(ResourceKey<LootTable> key, LootTable.Builder tableBuilder,
                                LootTableSource source, HolderLookup.Provider registries) {
        if (!source.isBuiltin() || !SPAWN_BONUS_CHEST.equals(key)) {
            return;
        }

        // ロール数1・エントリ1個だけの単独プールなので、他の抽選プールとは無関係に
        // 毎回必ず(重み付き抽選ではなく)そのまま追加される。
        tableBuilder.withPool(LootPool.lootPool()
                .add(LootItem.lootTableItem(WorldstitchMod.GATE_FRAME_BLOCK)
                        .apply(SetItemCountFunction.setCount(ContextIntProviders.exactly(16)))));

        tableBuilder.withPool(LootPool.lootPool()
                .add(LootItem.lootTableItem(WorldstitchMod.GATE_LINKER_ITEM)));
    }
}
