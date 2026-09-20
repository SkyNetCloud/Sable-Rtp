package ca.skynetcloud.sable_rtp.teleport;

import ca.skynetcloud.sable_rtp.Config;
import ca.skynetcloud.sable_rtp.Sable_rtp;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = Sable_rtp.MODID)
public class RtpWarmupManager {

    private static final double MOVEMENT_CANCEL_THRESHOLD_SQ = 0.25;

    private static final Map<UUID, PendingTeleport> PENDING = new HashMap<>();

    /** Counts down in ticks rather than wall-clock millis, so lag and pauses behave sanely. */
    private static final class PendingTeleport {
        int ticksLeft;
        final Vec3 startPos;
        final Runnable onComplete;

        PendingTeleport(int ticksLeft, Vec3 startPos, Runnable onComplete) {
            this.ticksLeft = ticksLeft;
            this.startPos = startPos;
            this.onComplete = onComplete;
        }
    }

    public static void schedule(ServerPlayer player, Runnable onComplete) {
        int warmupSeconds = Config.getTeleportWarmupSeconds();
        if (warmupSeconds <= 0) {
            onComplete.run();
            return;
        }

        PENDING.put(player.getUUID(), new PendingTeleport(warmupSeconds * 20, player.position(), onComplete));
        player.displayClientMessage(
                Component.translatable("message.warmup.start.text", warmupSeconds).withStyle(ChatFormatting.YELLOW),
                true);
    }

    public static boolean hasPending(UUID playerId) {
        return PENDING.containsKey(playerId);
    }

    public static boolean cancel(UUID playerId) {
        return PENDING.remove(playerId) != null;
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player entity = event.getEntity();
        if (entity.level().isClientSide) return;
        if (!(entity instanceof ServerPlayer player)) return;

        PendingTeleport pending = PENDING.get(player.getUUID());
        if (pending == null) return;

        if (player.position().distanceToSqr(pending.startPos) > MOVEMENT_CANCEL_THRESHOLD_SQ) {
            PENDING.remove(player.getUUID());
            player.displayClientMessage(
                    Component.translatable("message.warmup.cancelled.text").withStyle(ChatFormatting.RED), true);
            return;
        }

        if (--pending.ticksLeft <= 0) {
            PENDING.remove(player.getUUID());
            pending.onComplete.run();
            return;
        }

        // Only refresh the action bar on second boundaries instead of every tick.
        if (pending.ticksLeft % 20 == 0) {
            int secondsLeft = pending.ticksLeft / 20;
            player.displayClientMessage(
                    Component.translatable("message.warmup.counting.text", secondsLeft).withStyle(ChatFormatting.YELLOW),
                    true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() != null) {
            PENDING.remove(event.getEntity().getUUID());
        }
    }

    /** Drops any warmup whose player has vanished, e.g. after a dimension change. */
    public static void purgeInvalid() {
        Iterator<Map.Entry<UUID, PendingTeleport>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().ticksLeft <= 0) it.remove();
        }
    }
}