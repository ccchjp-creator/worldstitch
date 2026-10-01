package com.example.worldstitch.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu(導入されていれば)から、Worldstitchの一覧項目の歯車アイコンで
 * {@link WorldstitchConfigScreen} を開けるようにするための連携クラス。
 *
 * fabric.mod.json の entrypoints.modmenu にこのクラスを登録しているだけで、
 * ModMenu自体はこのMODの必須依存ではない(suggests扱い)。ModMenuが
 * 導入されていない環境ではこのクラスは一切読み込まれず、MOD本体の動作にも
 * 何の影響も無い。
 */
public class WorldstitchModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return WorldstitchConfigScreen::new;
    }
}
