package com.villagefall;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(VillageFall.MODID)
public class VillageFall {
    public static final String MODID = "villagefall";

    public VillageFall() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, FallConfig.SPEC);
        MinecraftForge.EVENT_BUS.register(new FallManager());
        MinecraftForge.EVENT_BUS.register(new PlayerAggression());
    }
}
