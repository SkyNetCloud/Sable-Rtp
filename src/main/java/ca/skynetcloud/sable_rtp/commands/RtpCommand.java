package ca.skynetcloud.sable_rtp.commands;

import ca.skynetcloud.sable_rtp.Config;
import ca.skynetcloud.sable_rtp.data.RtpCooldownData;
import ca.skynetcloud.sable_rtp.teleport.AsyncRtpSearch;
import ca.skynetcloud.sable_rtp.teleport.RtpWarmupManager;
import ca.skynetcloud.sable_rtp.teleport.SubLevelTeleporter;
import ca.skynetcloud.sable_rtp.utils.SubLevelUtils;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.UUID;

import static ca.skynetcloud.sable_rtp.Sable_rtp.MODID;
import static net.minecraft.commands.Commands.literal;

@EventBusSubscriber(modid = MODID)
public class RtpCommand {

    private static final int COOLDOWN_BYPASS_LEVEL = 2;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("sablertp")
                .requires(src -> src.hasPermission(0))
                .executes(RtpCommand::rtpExecution)
                .then(literal("cancel").executes(RtpCommand::cancelExecution)));
    }

    // --------------------------------------------------------------- /sablertp

    private static int rtpExecution(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();

        // The old code used `assert player != null`, which is a no-op at runtime. Running this from
        // console or a command block produced a raw NPE instead of a message.
        ServerPlayer serverPlayer = source.getPlayer();
        if (serverPlayer == null) {
            source.sendFailure(Component.translatable("message.playersonly.text"));
            return 0;
        }

        ServerLevel serverLevel = source.getLevel();
        UUID uuid = serverPlayer.getUUID();

        if (Config.isDimensionBlacklisted(serverLevel.dimension().location().toString())) {
            source.sendFailure(Component.translatable("message.dimensionblocked.text").withStyle(ChatFormatting.RED));
            return 0;
        }

        // This branch was missing its `return`, so a second /sablertp queued another full search.
        if (RtpWarmupManager.hasPending(uuid) || AsyncRtpSearch.isSearching(uuid)) {
            source.sendFailure(Component.translatable("message.pending.text").withStyle(ChatFormatting.RED));
            return 0;
        }

        RtpCooldownData cooldowns = RtpCooldownData.get(serverLevel.getServer());

        if (!source.hasPermission(COOLDOWN_BYPASS_LEVEL)) {
            long remaining = cooldowns.remainingSeconds(uuid, Config.getTeleportCooldownSeconds());
            if (remaining > 0) {
                source.sendFailure(Component.translatable("message.wait.text", fixDurationFormat(remaining))
                        .withStyle(ChatFormatting.RED));
                return 0;
            }
        }

        SubLevel subLevel = SubLevelUtils.resolve(serverPlayer);

        if (subLevel == null) {
            // Previously reported as "not server-side", which is a different failure entirely.
            source.sendFailure(Component.translatable("message.notonship.text").withStyle(ChatFormatting.RED));
            return 0;
        }
        if (!(subLevel instanceof ServerSubLevel serverSubLevel)) {
            source.sendFailure(Component.translatable("message.notserversided.text").withStyle(ChatFormatting.RED));
            return 0;
        }

        SubLevelUtils.VesselType vesselType = SubLevelUtils.classify(serverSubLevel);

        BoundingBox3dc bounds = serverSubLevel.boundingBox();
        double halfSizeX = (bounds.maxX() - bounds.minX()) / 2.0;
        double halfSizeZ = (bounds.maxZ() - bounds.minZ()) / 2.0;
        double belowPivot = SubLevelUtils.belowPivotOffset(serverSubLevel);

        int[] origin = resolveOrigin(serverLevel, bounds);

        source.sendSuccess(() -> Component.translatable("message.findingdestination.text")
                .withStyle(ChatFormatting.GRAY), false);

        AsyncRtpSearch.start(serverLevel, uuid, vesselType, origin[0], origin[1], halfSizeX, halfSizeZ, result -> {
            if (result == null) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.notsafe.text").withStyle(ChatFormatting.RED), false);
                return;
            }
            if (!serverPlayer.isAlive() || serverPlayer.hasDisconnected()) return;

            RtpWarmupManager.schedule(serverPlayer, () ->
                    completingTeleport(serverPlayer, serverSubLevel, serverLevel,
                            result.pos(), belowPivot, result.attempts()));
        });

        return 1;
    }

    private static int cancelExecution(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("message.playersonly.text"));
            return 0;
        }

        boolean warmupCancelled = RtpWarmupManager.cancel(player.getUUID());
        boolean searchCancelled = AsyncRtpSearch.isSearching(player.getUUID());
        if (searchCancelled) {
            AsyncRtpSearch.cancel(player.getUUID());
        }

        boolean cancelled = warmupCancelled || searchCancelled;
        String key = cancelled ? "message.cancelled.text" : "message.nothingtocancel.text";

        source.sendSuccess(() -> Component.translatable(key), false);
        return cancelled ? 1 : 0;
    }

    // ------------------------------------------------------------------ helpers

    private static int[] resolveOrigin(ServerLevel level, BoundingBox3dc bounds) {
        return switch (Config.getSearchOrigin()) {
            case WORLD_ORIGIN -> new int[]{0, 0};
            case WORLD_SPAWN -> {
                BlockPos spawn = level.getSharedSpawnPos();
                yield new int[]{spawn.getX(), spawn.getZ()};
            }
            case CURRENT_POSITION -> new int[]{
                    Mth.floor((bounds.minX() + bounds.maxX()) / 2.0),
                    Mth.floor((bounds.minZ() + bounds.maxZ()) / 2.0)
            };
        };
    }

    private static void completingTeleport(ServerPlayer serverPlayer, ServerSubLevel serverSubLevel,
                                           ServerLevel serverLevel, BlockPos destination,
                                           double belowPivot, int attemptsUsed) {
        boolean okay = SubLevelTeleporter.teleport(serverSubLevel, serverLevel, destination, belowPivot);

        if (!okay) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.teleportfailed.text").withStyle(ChatFormatting.RED), false);
            return;
        }

        RtpCooldownData.get(serverLevel.getServer()).markUsed(serverPlayer.getUUID(), System.currentTimeMillis());

        Component destinationMsg = Component.translatable("message.destinationcoords.text",
                destination.getX(), destination.getY(), destination.getZ());
        serverPlayer.displayClientMessage(
                Component.translatable("message.destinationfound.text", attemptsUsed, destinationMsg)
                        .withStyle(ChatFormatting.GOLD), false);
    }

    private static String fixDurationFormat(long totalSeconds) {
        long hours = totalSeconds / 3600;
        long mins = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) return hours + "h " + mins + "m " + seconds + "s";
        return mins > 0 ? (mins + "m " + seconds + "s") : (seconds + "s");
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        // Cancel in-flight work, but do NOT clear the cooldown — that was a free reset on relog.
        UUID uuid = event.getEntity().getUUID();
        AsyncRtpSearch.cancel(uuid);
        RtpWarmupManager.cancel(uuid);
    }
}