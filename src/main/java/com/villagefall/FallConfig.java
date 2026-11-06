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
    public static final ForgeConfigSpec.BooleanValue AUTO_WAR;
    public static final ForgeConfigSpec.IntValue DISMANTLE_BLOCKS;
    public static final ForgeConfigSpec.IntValue ARSON_ATTEMPTS;
    public static final ForgeConfigSpec.IntValue ARSON_WINDOW_SECONDS;
    public static final ForgeConfigSpec.IntValue TERRITORY_MARGIN;
    public static final ForgeConfigSpec.IntValue MOLOTOV_RANGE;
    public static final ForgeConfigSpec.IntValue FLAME_ATTEMPTS;
    public static final ForgeConfigSpec.IntValue TNT_ATTEMPTS;
    public static final ForgeConfigSpec.IntValue HIT_ATTEMPTS;
    public static final ForgeConfigSpec.BooleanValue CAMPFIRES;
    public static final ForgeConfigSpec.BooleanValue CAMPFIRE_REQUIRE_SNOW;
    public static final ForgeConfigSpec.IntValue CAMPFIRE_MAX_NEAR;
    public static final ForgeConfigSpec.IntValue CAMPFIRE_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue FIREFIGHT;
    public static final ForgeConfigSpec.IntValue FIREFIGHT_RANGE;
    public static final ForgeConfigSpec.BooleanValue RETREAT_ENABLED;
    public static final ForgeConfigSpec.IntValue RETREAT_PERCENT;
    public static final ForgeConfigSpec.IntValue LEADER_FLAG_MINUTES;
    public static final ForgeConfigSpec.IntValue REFUGE_RANGE;
    public static final ForgeConfigSpec.IntValue FLEE_DISTANCE;
    public static final ForgeConfigSpec.IntValue FLEE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue ARSENAL;
    public static final ForgeConfigSpec.IntValue ARSENAL_MIN_DEFENDERS;
    public static final ForgeConfigSpec.IntValue SHARE_POTATO;
    public static final ForgeConfigSpec.IntValue SHARE_FLAME;
    public static final ForgeConfigSpec.IntValue SHARE_OCTO;
    public static final ForgeConfigSpec.IntValue SHARE_MOLOTOV;
    public static final ForgeConfigSpec.IntValue SHARE_BAZOOKA;
    public static final ForgeConfigSpec.BooleanValue GOLEMS;
    public static final ForgeConfigSpec.IntValue GOLEM_PERCENT;
    public static final ForgeConfigSpec.IntValue GOLEM_MIN;
    public static final ForgeConfigSpec.IntValue GOLEM_MAX;

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
        AUTO_WAR = b.comment("A player who is in a faction and attacks a village soldier automatically declares the normal war (conquest) on that village. Players without a faction wage a war of annihilation instead.")
                .define("autoDeclareWar", true);
        DISMANTLE_BLOCKS = b.comment("Breaking this many blocks inside a village within a minute counts as dismantling it (same as attacking its soldiers).")
                .defineInRange("dismantleBlocks", 12, 1, 200);
        ARSON_ATTEMPTS = b.comment("Lighting fires (flint and steel, fire charge, lava) inside a village this many times within arsonWindowSeconds counts as an attack on it.")
                .defineInRange("arsonAttempts", 6, 1, 100);
        ARSON_WINDOW_SECONDS = b.comment("Time window for counting arson attempts.")
                .defineInRange("arsonWindowSeconds", 120, 10, 3600);
        TERRITORY_MARGIN = b.comment("Territory of a village = its box expanded by this many blocks. Placing or lighting TNT inside it is an attack.")
                .defineInRange("territoryMargin", 24, 0, 128);
        MOLOTOV_RANGE = b.comment("A molotov thrown by a player counts as an attack if its flight line crosses a village's territory within this distance (blocks).")
                .defineInRange("molotovRange", 80, 8, 256);
        FLAME_ATTEMPTS = b.comment("Seconds of using a flamethrower at a village (within the arson window) that count as an attack. Bazooka shots and molotovs count at once.")
                .defineInRange("flamethrowerSeconds", 3, 1, 100);
        TNT_ATTEMPTS = b.comment("TNT blocks placed on a village's territory (within the window) that count as an attack. Lighting TNT counts at once.")
                .defineInRange("tntPlaced", 3, 1, 100);
        HIT_ATTEMPTS = b.comment("Hits on ordinary villagers of a village (within the window) that count as an attack. Hitting its soldiers counts at once.")
                .defineInRange("villagerHits", 3, 1, 100);
        b.pop();
        b.push("winter");
        CAMPFIRES = b.comment("In winter snowfall ordinary villagers light campfires near their gathering place.")
                .define("campfires", true);
        CAMPFIRE_REQUIRE_SNOW = b.comment("If true: only while it is actually snowing in that spot. If false (default): also in Serene Seasons winter (non-hot biome) or when snow lies around the villager.")
                .define("requireSnowfall", false);
        CAMPFIRE_MAX_NEAR = b.comment("Do not place a new campfire if this many already burn within 10 blocks.")
                .defineInRange("maxCampfiresNear", 2, 1, 10);
        CAMPFIRE_COOLDOWN_SECONDS = b.comment("Pause between campfires lit by villagers of the same area.")
                .defineInRange("cooldownSeconds", 180, 10, 3600);
        b.pop();
        b.push("firefighting");
        FIREFIGHT = b.comment("Ordinary villagers put out fires by pouring water on them and pick the water up afterwards.")
                .define("enabled", true);
        FIREFIGHT_RANGE = b.comment("Villagers notice fires within this many blocks.")
                .defineInRange("range", 14, 4, 48);
        b.pop();
        b.push("arsenal");
        ARSENAL = b.comment("A share of the DEFENDERS (soldiers) of every village (own, joined and NPC) carries special weapons.")
                .define("enabled", true);
        ARSENAL_MIN_DEFENDERS = b.comment("Villages with fewer defenders than this get no special weapons.")
                .defineInRange("minDefenders", 5, 1, 200);
        SHARE_POTATO = b.comment("Percent of defenders with a Create potato cannon (rounded up).")
                .defineInRange("potatoCannonPercent", 8, 0, 100);
        SHARE_FLAME = b.comment("Percent of defenders with a flamethrower (From Another World).")
                .defineInRange("flamethrowerPercent", 5, 0, 100);
        SHARE_OCTO = b.comment("Percent of foot defenders with an Octo Arms tentacle rig (never riders).")
                .defineInRange("octoRigPercent", 5, 0, 100);
        SHARE_MOLOTOV = b.comment("Percent of defenders with molotovs.")
                .defineInRange("molotovPercent", 10, 0, 100);
        SHARE_BAZOOKA = b.comment("Percent of defenders with a bazooka.")
                .defineInRange("bazookaPercent", 4, 0, 100);
        b.pop();
        b.push("golems");
        GOLEMS = b.comment("Villages (own, joined and NPC) also keep iron golems.")
                .define("enabled", true);
        GOLEM_PERCENT = b.comment("Golems as percent of the whole population (recruits + civilians), rounded up.")
                .defineInRange("percent", 5, 0, 100);
        GOLEM_MIN = b.comment("Minimum number of golems: spawned from the very start.")
                .defineInRange("min", 2, 0, 50);
        GOLEM_MAX = b.comment("Upper limit of golems per village.")
                .defineInRange("max", 12, 1, 100);
        b.pop();
        b.push("retreat");
        RETREAT_ENABLED = b.comment("When the leader is killed by an enemy of the village (hostile mobs, barbarians, infected, a player without a faction) and few villagers are left, the survivors flee.")
                .define("enabled", true);
        RETREAT_PERCENT = b.comment("Survivors flee when fewer than this percent of the village population (recruits + civilians) is left.")
                .defineInRange("belowPercent", 30, 1, 99);
        LEADER_FLAG_MINUTES = b.comment("How long after the leader's death the village may still retreat.")
                .defineInRange("leaderFlagMinutes", 15, 1, 120);
        REFUGE_RANGE = b.comment("Survivors who hold out 5 more seconds are teleported to the nearest other village within this many blocks and join it.")
                .defineInRange("refugeRange", 800, 64, 4000);
        FLEE_DISTANCE = b.comment("With no village in range, survivors run this many blocks away and the recruits become free mercenaries.")
                .defineInRange("fleeDistance", 200, 50, 600);
        FLEE_SECONDS = b.comment("Longest time the flight takes before it is resolved.")
                .defineInRange("fleeSeconds", 60, 10, 600);
        b.pop();
        SPEC = b.build();
    }
}
