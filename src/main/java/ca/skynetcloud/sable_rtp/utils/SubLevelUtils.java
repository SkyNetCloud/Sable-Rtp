package ca.skynetcloud.sable_rtp.utils;

import ca.skynetcloud.sable_rtp.Config;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SubLevelUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger("sable_rtp");
    private static final boolean DEBUG = false;

    public enum VesselType {
        GROUND, AIRSHIP, BOAT
    }

    // ---------------------------------------------------------------- resolve

    public static SubLevel resolve(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            SubLevel viaVehicle = Sable.HELPER.getTrackingSubLevel(vehicle);
            if (viaVehicle != null) return viaVehicle;
        }
        return Sable.HELPER.getTrackingSubLevel(player);
    }

    public static VesselType classify(ServerSubLevel subLevel) {
        if (isInWater(subLevel)) return VesselType.BOAT;
        if (isAirborne(subLevel)) return VesselType.AIRSHIP;
        return VesselType.GROUND;
    }

    /**
     * Distance from the sub-level's pivot down to its lowest block. The destination Y produced by the
     * search describes where the *bottom* of the contraption should sit, so this offset has to be added
     * back on before the pose is written — otherwise anything with a non-basal pivot lands buried.
     */
    public static double belowPivotOffset(ServerSubLevel subLevel) {
        return subLevel.logicalPose().position().y() - subLevel.boundingBox().minY();
    }

    // ------------------------------------------------------------ classifiers

    public static boolean isAirborne(ServerSubLevel subLevel) {
        BoundingBox3dc bounds = subLevel.boundingBox();
        ServerLevel level = subLevel.getLevel();

        double lowestPoint = bounds.minY();
        int centerX = Mth.floor((bounds.minX() + bounds.maxX()) / 2.0);
        int centerZ = Mth.floor((bounds.minZ() + bounds.maxZ()) / 2.0);

        int terrainHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        return lowestPoint - terrainHeight > 5.0;
    }

    public static boolean isInWater(ServerSubLevel subLevel) {
        BoundingBox3dc bounds = subLevel.boundingBox();
        ServerLevel level = subLevel.getLevel();

        double minX = bounds.minX(), maxX = bounds.maxX();
        double minZ = bounds.minZ(), maxZ = bounds.maxZ();
        int y = Mth.floor(bounds.minY());

        double[][] samplePoints = {
                {minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ},
                {(minX + maxX) / 2.0, (minZ + maxZ) / 2.0}
        };

        int waterCount = 0;
        for (double[] point : samplePoints) {
            BlockPos checkPos = new BlockPos(Mth.floor(point[0]), y, Mth.floor(point[1]));
            if (!level.isInWorldBounds(checkPos)) continue;
            if (isWater(level, checkPos)) waterCount++;
        }

        return (double) waterCount / samplePoints.length > 0.6;
    }

    // ----------------------------------------------------------- block tests

    private static boolean isWater(ServerLevel level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        return fluid.getType() == Fluids.WATER || fluid.getType() == Fluids.FLOWING_WATER;
    }

    private static boolean isSolidBlock(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /** Tag-based, so it survives non-English servers and modded wood. */
    private static boolean isTreeBlock(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }

    /** Grass, flowers, ferns, carpets, snow layers — anything a landing contraption can crush. */
    private static boolean isPassable(ServerLevel level, BlockPos pos, BlockState state) {
        return state.isAir()
                || state.canBeReplaced()
                || state.getCollisionShape(level, pos).isEmpty();
    }

    // ---------------------------------------------------- coarse (pre-gen) test

    /**
     * Cheap first-pass filter that only needs the world-gen heightmaps, available at
     * {@code ChunkStatus.NOISE}. Lets us reject the vast majority of candidates before paying for
     * feature generation, block entities and lighting.
     *
     * @return the candidate Y for the bottom of the contraption, or {@code null} to reject outright.
     */
    public static Integer coarseCandidateY(ServerLevel level, int surfaceY, int oceanFloorY,
                                           VesselType vesselType, net.minecraft.util.RandomSource rand) {
        int waterDepth = surfaceY - oceanFloorY;
        int seaLevel = level.getSeaLevel();
        int minY = level.getMinBuildHeight() + 5;
        int maxY = level.getMaxBuildHeight() - 5;

        switch (vesselType) {
            case BOAT -> {
                if (waterDepth < Config.getBoatMinWaterDepth()) return null;
                int y = surfaceY - 1;
                return (y <= minY || y >= maxY) ? null : y;
            }
            case GROUND -> {
                if (waterDepth > 0) return null;
                if (oceanFloorY < seaLevel + 1) return null;
                int y = oceanFloorY;
                return (y <= minY || y >= maxY - Config.getGroundClearance()) ? null : y;
            }
            case AIRSHIP -> {
                int base = Math.max(surfaceY, seaLevel);
                int minAir = Config.getAirshipMinHeight();
                int maxAir = Config.getAirshipMaxHeight();
                int headroom = maxY - base - 12;
                if (headroom < minAir) return null;

                maxAir = Math.min(maxAir, headroom);
                int y = base + minAir + rand.nextInt(Math.max(1, maxAir - minAir + 1));
                return (y <= minY || y >= maxY) ? null : y;
            }
        }
        return null;
    }

    // -------------------------------------------------- full (loaded) test

    public static boolean isSafeDestination(ServerLevel level, BlockPos pos, VesselType vesselType,
                                            double halfSizeX, double halfSizeZ) {
        if (!level.isInWorldBounds(pos)) return false;
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
        if (pos.getY() >= level.getMaxBuildHeight() - 5) return false;
        if (pos.getY() <= level.getMinBuildHeight() + 5) return false;

        return switch (vesselType) {
            case GROUND -> isSafeGround(level, pos, halfSizeX, halfSizeZ);
            case AIRSHIP -> isSafeAir(level, pos, halfSizeX, halfSizeZ);
            case BOAT -> isSafeWater(level, pos, halfSizeX, halfSizeZ);
        };
    }

    /** Sample radius that actually reflects the contraption footprint, instead of a hardcoded 2. */
    private static int footprintRadius(double halfSizeX, double halfSizeZ) {
        int r = Mth.ceil(Math.max(halfSizeX, halfSizeZ));
        return Mth.clamp(r, 2, 24);
    }

    private static boolean isSafeGround(ServerLevel level, BlockPos pos, double halfSizeX, double halfSizeZ) {
        if (pos.getY() < level.getSeaLevel() + 1) return false;

        BlockPos below = pos.below();
        if (!isSolidBlock(level, below) || isWater(level, below)) return false;
        if (isWater(level, pos) || isWater(level, pos.above())) return false;

        int clearance = Config.getGroundClearance();
        int radius = footprintRadius(halfSizeX, halfSizeZ);
        int step = Math.max(1, radius / 4);

        int samples = 0;
        int obstacles = 0;
        int treeBlocks = 0;
        int unsupported = 0;

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                BlockPos column = pos.offset(dx, 0, dz);
                if (!level.isInWorldBounds(column)) continue;
                samples++;

                if (isWater(level, column) || isWater(level, column.below())) return false;

                if (!isSolidBlock(level, column.below())) unsupported++;

                for (int h = 0; h < clearance; h++) {
                    BlockPos check = column.above(h);
                    BlockState state = level.getBlockState(check);
                    if (isPassable(level, check, state)) continue;

                    if (isTreeBlock(state)) {
                        treeBlocks++;
                        // A trunk right under the hull is an instant reject.
                        if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) return false;
                    } else {
                        obstacles++;
                    }
                    break;
                }
            }
        }

        if (samples == 0) return false;
        if ((double) obstacles / samples > 0.35) return false;
        if ((double) treeBlocks / samples > 0.40) return false;
        if ((double) unsupported / samples > 0.30) return false;

        return true;
    }

    private static boolean isSafeAir(ServerLevel level, BlockPos pos, double halfSizeX, double halfSizeZ) {
        int radius = footprintRadius(halfSizeX, halfSizeZ);
        int step = Math.max(1, radius / 2);
        int vertical = 10;

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                for (int dy = 0; dy < vertical; dy++) {
                    BlockPos check = pos.offset(dx, dy, dz);
                    if (!level.isInWorldBounds(check)) return false;
                    if (!level.getBlockState(check).isAir()) return false;
                }
            }
        }
        return true;
    }

    private static boolean isSafeWater(ServerLevel level, BlockPos pos, double halfSizeX, double halfSizeZ) {
        if (!isWater(level, pos)) return false;

        int radius = footprintRadius(halfSizeX, halfSizeZ);
        int step = Math.max(1, radius / 3);
        int minDepth = Config.getBoatMinWaterDepth();

        int samples = 0;
        int waterSamples = 0;

        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                BlockPos column = pos.offset(dx, 0, dz);
                if (!level.isInWorldBounds(column)) continue;
                samples++;
                if (!isWater(level, column)) continue;

                int depth = 0;
                for (int i = 1; i <= minDepth; i++) {
                    if (!isWater(level, column.below(i))) break;
                    depth++;
                }
                if (depth < minDepth) continue;

                boolean clearAbove = true;
                for (int i = 1; i <= 5; i++) {
                    BlockPos above = column.above(i);
                    if (!level.isInWorldBounds(above)) { clearAbove = false; break; }
                    BlockState state = level.getBlockState(above);
                    if (!state.isAir() && !isWater(level, above)) { clearAbove = false; break; }
                }
                if (clearAbove) waterSamples++;
            }
        }

        if (samples == 0) return false;
        boolean ok = (double) waterSamples / samples >= 0.85;
        if (!ok && DEBUG) LOGGER.debug("Boat reject at {}: {}/{} usable water samples", pos, waterSamples, samples);
        return ok;
    }
}