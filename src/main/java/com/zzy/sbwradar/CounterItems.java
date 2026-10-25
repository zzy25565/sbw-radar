package com.zzy.sbwradar;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Countermeasure cartridges carried as items (finite rounds, no auto replenish).
 *
 *  chaff  - defeats RADAR-guided missiles only
 *  flare  - defeats IR-guided missiles only
 */
public final class CounterItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SbwRadarMod.MODID);

    public static final DeferredItem<Item> CHAFF = ITEMS.register("chaff",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static final DeferredItem<Item> FLARE = ITEMS.register("flare",
            () -> new Item(new Item.Properties().stacksTo(64)));

    public static void init(IEventBus bus) {
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
                event.accept(CHAFF);
                event.accept(FLARE);
            }
        });
    }

    private CounterItems() {}
}
