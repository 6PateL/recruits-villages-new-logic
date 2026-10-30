package com.villagefall;

import net.minecraftforge.common.ForgeConfigSpec;

public class FallConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue PLAYER_FACTIONS;
    public static final ForgeConfigSpec.IntValue MIN_ATTACKERS;
    public static final ForgeConfigSpec.IntValue SIEGE_SECONDS;
    public static final ForgeConfigSpec.IntValue FALL_GARRISON;
    public static final ForgeConfigSpec.IntValue FALL_GRACE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue FREEZE_REGROWTH;
    public static final ForgeConfigSpec.BooleanValue WIPE_FALLS;
    public static final ForgeConfigSpec.IntValue SCAN_RADIUS;
    public static final ForgeConfigSpec.BooleanValue ANNOUNCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("fall");
        ENABLED = b.comment("Besieged villages can fall.").define("enabled", true);
        PLAYER_FACTIONS = b.comment("Also let player-founded villages fall.").define("includePlayerFactions", false);
        MIN_ATTACKERS = b.comment("A village counts as attacked when at least this many hostile mobs (zombies, barbarians, infected...) are inside it.")
                .defineInRange("minAttackers", 3, 1, 50);
        SIEGE_SECONDS = b.comment("Seconds of continuous attack before the village counts as besieged (then it stops growing back).")
                .defineInRange("siegeSeconds", 20, 1, 600);
        FALL_GARRISON = b.comment("A besieged village falls when its garrison drops to this many recruits or fewer (0 = until every recruit is dead).")
                .defineInRange("fallGarrison", 0, 0, 50);
        FALL_GRACE_SECONDS = b.comment("The fall condition must hold this many seconds in a row (time for defenders / players to turn the tide).")
                .defineInRange("fallGraceSeconds", 45, 1, 3600);
        FREEZE_REGROWTH = b.comment("While besieged, population and recruit growth is frozen (no instant refill).")
                .define("freezeRegrowth", true);
        WIPE_FALLS = b.comment("A village with no recruits and no civilians at all is removed even without a siege.")
                .define("wipeFalls", true);
        SCAN_RADIUS = b.comment("Radius (blocks) around the village centre in which attackers are counted.")
                .defineInRange("scanRadius", 70, 16, 200);
        ANNOUNCE = b.comment("Tell all players when a village falls (name and coordinates).").define("announce", true);
        b.pop();
        SPEC = b.build();
    }
}
