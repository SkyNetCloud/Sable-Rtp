package ca.skynetcloud.sable_rtp;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

@EventBusSubscriber(modid = Sable_rtp.MODID)
public class Config {

    public enum SearchOrigin {
        WORLD_ORIGIN,
        WORLD_SPAWN,
        CURRENT_POSITION
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.EnumValue<SearchOrigin> SEARCH_ORIGIN = BUILDER
            .comment("Where the random destination roll is centred.")
            .translation("sable_rtp.configuration.searchOrigin")
            .defineEnum("searchOrigin", SearchOrigin.WORLD_ORIGIN);

    private static final ModConfigSpec.IntValue TELEPORT_SEARCH_RADIUS = BUILDER
            .comment("Maximum radius (in blocks) from the search origin to look for a destination.")
            .translation("sable_rtp.configuration.teleportSearchRadius")
            .defineInRange("teleportSearchRadius", 5000, 1, 29_999_000);

    private static final ModConfigSpec.IntValue MIN_TELEPORT_RADIUS = BUILDER
            .comment("Minimum radius (in blocks) from the search origin. Stops /sablertp dumping you next door.")
            .translation("sable_rtp.configuration.minTeleportRadius")
            .defineInRange("minTeleportRadius", 500, 0, 29_999_000);

    private static final ModConfigSpec.IntValue MAX_LOCATION_LOOKUP_ATTEMPTS = BUILDER
            .comment("Maximum number of candidate positions to test before giving up.")
            .translation("sable_rtp.configuration.maxLocationLookupAttempts")
            .defineInRange("maxLocationLookupAttempts", 30, 1, 1000);

    private static final ModConfigSpec.IntValue GROUND_CLEARANCE = BUILDER
            .comment("Vertical clearance (in blocks) required above the landing surface for a ground vehicle.")
            .translation("sable_rtp.configuration.groundClearance")
            .defineInRange("groundClearance", 3, 1, 32);

    private static final ModConfigSpec.IntValue AIRSHIP_MIN_HEIGHT = BUILDER
            .comment("Minimum height ABOVE LOCAL TERRAIN (not absolute Y) that an airship may be placed at.")
            .translation("sable_rtp.configuration.airshipMinHeight")
            .defineInRange("airshipMinHeight", 40, 1, 2031);

    private static final ModConfigSpec.IntValue AIRSHIP_MAX_HEIGHT = BUILDER
            .comment("Maximum height ABOVE LOCAL TERRAIN (not absolute Y) that an airship may be placed at.")
            .translation("sable_rtp.configuration.airshipMaxHeight")
            .defineInRange("airshipMaxHeight", 150, 1, 2031);

    private static final ModConfigSpec.IntValue BOAT_MIN_WATER_DEPTH = BUILDER
            .comment("Minimum water depth (in blocks) required under a waterborne contraption.")
            .translation("sable_rtp.configuration.boatMinWaterDepth")
            .defineInRange("boatMinWaterDepth", 5, 1, 64);

    private static final ModConfigSpec.IntValue TELEPORT_COOLDOWN_SECONDS = BUILDER
            .comment("Cooldown, in seconds, between successful uses of /sablertp per player.",
                    "Persists across relogs and server restarts.")
            .translation("sable_rtp.configuration.teleportCooldownSeconds")
            .defineInRange("teleportCooldownSeconds", 600, 0, Integer.MAX_VALUE);

    private static final ModConfigSpec.IntValue TELEPORT_WARMUP_SECONDS = BUILDER
            .comment("Delay, in seconds, after a destination is found before the contraption is actually moved.",
                    "The teleport is cancelled (with no cooldown penalty) if the player moves during this delay.",
                    "Set to 0 to teleport instantly.")
            .translation("sable_rtp.configuration.teleportWarmupSeconds")
            .defineInRange("teleportWarmupSeconds", 3, 0, 3600);

    private static final ModConfigSpec.IntValue SEARCH_CANDIDATES_PER_TICK = BUILDER
            .comment("How many destination candidates each active search may advance per server tick.",
                    "Lower values spread world generation cost out further; higher values find a spot sooner.")
            .translation("sable_rtp.configuration.searchCandidatesPerTick")
            .defineInRange("searchCandidatesPerTick", 1, 1, 16);

    private static final ModConfigSpec.ConfigValue<List<? extends String>> DIMENSION_BLACKLIST = BUILDER
            .comment("Dimension IDs in which /sablertp is refused, e.g. [\"minecraft:the_end\", \"minecraft:the_nether\"].")
            .translation("sable_rtp.configuration.dimensionBlacklist")
            .defineListAllowEmpty("dimensionBlacklist",
                    List.of("minecraft:the_end"),
                    () -> "minecraft:overworld",
                    o -> o instanceof String);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static SearchOrigin getSearchOrigin() {
        return SEARCH_ORIGIN.get();
    }

    public static int getTeleportSearchRadius() {
        return TELEPORT_SEARCH_RADIUS.get();
    }

    /** Clamped so a misconfigured min can never exceed max. */
    public static int getMinTeleportRadius() {
        return Math.min(MIN_TELEPORT_RADIUS.get(), TELEPORT_SEARCH_RADIUS.get());
    }

    public static int getMaxLocationLookupAttempts() {
        return MAX_LOCATION_LOOKUP_ATTEMPTS.get();
    }

    public static int getGroundClearance() {
        return GROUND_CLEARANCE.get();
    }

    public static int getAirshipMinHeight() {
        return AIRSHIP_MIN_HEIGHT.get();
    }

    /** Clamped so a misconfigured max can never fall below min. */
    public static int getAirshipMaxHeight() {
        return Math.max(AIRSHIP_MAX_HEIGHT.get(), AIRSHIP_MIN_HEIGHT.get() + 1);
    }

    public static int getBoatMinWaterDepth() {
        return BOAT_MIN_WATER_DEPTH.get();
    }

    public static int getTeleportCooldownSeconds() {
        return TELEPORT_COOLDOWN_SECONDS.get();
    }

    public static int getTeleportWarmupSeconds() {
        return TELEPORT_WARMUP_SECONDS.get();
    }

    public static int getSearchCandidatesPerTick() {
        return SEARCH_CANDIDATES_PER_TICK.get();
    }

    public static boolean isDimensionBlacklisted(String dimensionId) {
        return DIMENSION_BLACKLIST.get().contains(dimensionId);
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
    }
}