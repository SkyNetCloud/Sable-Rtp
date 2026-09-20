package ca.skynetcloud.sable_rtp.teleport;

import ca.skynetcloud.sable_rtp.Config;
import ca.skynetcloud.sable_rtp.Sable_rtp;
import ca.skynetcloud.sable_rtp.utils.SubLevelUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


@EventBusSubscriber(modid = Sable_rtp.MODID)
public final class AsyncRtpSearch {

    private static final TicketType<ChunkPos> RTP_TICKET = TicketType.create("sable_rtp_destination", Comparator.comparingLong(ChunkPos::toLong), 20 * 30);

    private static final List<Search> ACTIVE = new ArrayList<>();

    private AsyncRtpSearch() {
    }

    public record Result(BlockPos pos, int attempts) {
    }

    public static void start(ServerLevel level, UUID owner, SubLevelUtils.VesselType vesselType, int originX, int originZ, double halfSizeX, double halfSizeZ, Consumer<Result> onDone) {
        ACTIVE.add(new Search(level, owner, vesselType, originX, originZ, halfSizeX, halfSizeZ, onDone));
    }

    public static boolean isSearching(UUID owner) {
        return ACTIVE.stream().anyMatch(s -> s.owner.equals(owner) && !s.finished);
    }

    public static void cancel(UUID owner) {
        ACTIVE.removeIf(s -> s.owner.equals(owner));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) return;

        int budget = Config.getSearchCandidatesPerTick();

        Iterator<Search> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Search search = it.next();
            for (int i = 0; i < budget && !search.finished && !search.waiting(); i++) {
                search.step();
            }
            if (!search.finished) search.step(); // lets a completed future be consumed
            if (search.finished) it.remove();
        }
    }

    // ------------------------------------------------------------------ state

    private static final class Search {
        private final ServerLevel level;
        private final UUID owner;
        private final SubLevelUtils.VesselType vesselType;
        private final int originX;
        private final int originZ;
        private final double halfSizeX;
        private final double halfSizeZ;
        private final Consumer<Result> onDone;
        private final RandomSource rand;
        private final int maxAttempts;

        private int attempts;
        private int x;
        private int z;
        private int candidateY;
        private boolean coarseStage;
        private boolean finished;
        private CompletableFuture<ChunkResult<ChunkAccess>> pending;

        Search(ServerLevel level, UUID owner, SubLevelUtils.VesselType vesselType, int originX, int originZ, double halfSizeX, double halfSizeZ, Consumer<Result> onDone) {
            this.level = level;
            this.owner = owner;
            this.vesselType = vesselType;
            this.originX = originX;
            this.originZ = originZ;
            this.halfSizeX = halfSizeX;
            this.halfSizeZ = halfSizeZ;
            this.onDone = onDone;
            this.rand = level.getRandom();
            this.maxAttempts = Config.getMaxLocationLookupAttempts();
        }

        boolean waiting() {
            return pending != null && !pending.isDone();
        }

        void step() {
            if (finished) return;

            if (pending != null) {
                if (!pending.isDone()) return;
                ChunkResult<ChunkAccess> result = pending.getNow(null);
                pending = null;

                ChunkAccess chunk = (result != null && result.isSuccess()) ? result.orElse(null) : null;
                if (chunk == null) {
                    nextCandidate();
                    return;
                }

                if (coarseStage) {
                    handleCoarse(chunk);
                } else {
                    handleFull();
                }
                return;
            }

            nextCandidate();
        }

        private void handleCoarse(ChunkAccess chunk) {
            int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
            int floorY = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);

            Integer y = SubLevelUtils.coarseCandidateY(level, surfaceY, floorY, vesselType, rand);
            if (y == null) {
                nextCandidate();
                return;
            }

            candidateY = y;
            coarseStage = false;

            // Hold the area briefly so the destination cannot unload during the warmup.
            ChunkPos cp = new ChunkPos(x >> 4, z >> 4);
            level.getChunkSource().addRegionTicket(RTP_TICKET, cp, 3, cp);
            pending = level.getChunkSource().getChunkFuture(cp.x, cp.z, ChunkStatus.FULL, true);
        }

        private void handleFull() {
            BlockPos candidate = new BlockPos(x, candidateY, z);
            if (SubLevelUtils.isSafeDestination(level, candidate, vesselType, halfSizeX, halfSizeZ)) {
                finish(new Result(candidate, attempts));
            } else {
                nextCandidate();
            }
        }

        private void nextCandidate() {
            if (attempts >= maxAttempts) {
                finish(null);
                return;
            }
            attempts++;

            int minR = Config.getMinTeleportRadius();
            int maxR = Config.getTeleportSearchRadius();

            // sqrt keeps the roll uniform over area rather than over radius, so you aren't biased inward.
            double t = rand.nextDouble();
            double r = Math.sqrt(minR * (double) minR + t * (maxR * (double) maxR - minR * (double) minR));
            double theta = rand.nextDouble() * Math.PI * 2.0;

            x = originX + Mth.floor(Math.cos(theta) * r);
            z = originZ + Mth.floor(Math.sin(theta) * r);

            BlockPos flat = new BlockPos(x, level.getSeaLevel(), z);
            if (!level.getWorldBorder().isWithinBounds(flat) || !level.isInWorldBounds(flat)) {
                // Cheap rejects don't need to cost a tick.
                nextCandidate();
                return;
            }

            coarseStage = true;
            pending = level.getChunkSource().getChunkFuture(x >> 4, z >> 4, ChunkStatus.NOISE, true);
        }

        private void finish(Result result) {
            finished = true;
            pending = null;
            try {
                onDone.accept(result);
            } catch (Exception e) {
                Sable_rtp.LOGGER.error("RTP search callback failed", e);
            }
        }
    }
}