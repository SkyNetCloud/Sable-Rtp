package ca.skynetcloud.sable_rtp.teleport;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SubLevelTeleporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("sable_rtp");

    /** How far outside the hull to sweep for riders, pets, dropped items and so on. */
    private static final double ENTITY_SWEEP_MARGIN = 2.0;

    private record Mount(Entity passenger, Entity vehicle) {
    }

    /**
     * @param dest       the block the <em>bottom</em> of the contraption should sit on
     * @param belowPivot distance from the sub-level pivot down to its lowest block; see
     *                   {@code SubLevelUtils.belowPivotOffset}. Without this the pose is written as if
     *                   the pivot were the keel, and anything with a centred pivot lands half-buried.
     */
    public static boolean teleport(ServerSubLevel subLevel, ServerLevel level, BlockPos dest, double belowPivot) {
        if (subLevel == null) return false;

        try {
            Pose3d currentPose = subLevel.logicalPose();
            Quaterniond currentOrientation = currentPose.orientation();
            Vector3d currentPos = currentPose.position();

            Vector3d newPosition = new Vector3d(
                    dest.getX() + 0.5,
                    dest.getY() + belowPivot,
                    dest.getZ() + 0.5
            );

            double dx = newPosition.x - currentPos.x;
            double dy = newPosition.y - currentPos.y;
            double dz = newPosition.z - currentPos.z;

            // Sweep every entity on board, not just tracking players: mobs, pets, armour stands,
            // dropped items and item frames were all being left behind.
            BoundingBox3dc bounds = subLevel.boundingBox();
            AABB sweep = new AABB(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ()
            ).inflate(ENTITY_SWEEP_MARGIN);

            List<Entity> riders = new ArrayList<>(level.getEntities((Entity) null, sweep, e -> !e.isRemoved()));
            Set<Entity> riderSet = new HashSet<>(riders);

            // Remember the mount graph, because teleporting dismounts passengers.
            List<Mount> mounts = new ArrayList<>();
            for (Entity entity : riders) {
                Entity vehicle = entity.getVehicle();
                if (vehicle != null && riderSet.contains(vehicle)) {
                    mounts.add(new Mount(entity, vehicle));
                }
            }
            for (Entity entity : riders) {
                entity.stopRiding();
            }

            PhysicsPipeline pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
            pipeline.teleport(subLevel, newPosition, currentOrientation);

            for (Entity entity : riders) {
                if (entity.isRemoved()) continue;
                entity.teleportTo(level,
                        entity.getX() + dx,
                        entity.getY() + dy,
                        entity.getZ() + dz,
                        Set.of(),
                        entity.getYRot(),
                        entity.getXRot());
            }

            for (Mount mount : mounts) {
                if (mount.passenger().isRemoved() || mount.vehicle().isRemoved()) continue;
                mount.passenger().startRiding(mount.vehicle(), true);
            }

            String subLevelName = subLevel.getName() != null ? subLevel.getName() : "unnamed";
            LOGGER.info("Teleported sub-level '{}' (id={}) to {} in {} carrying {} entities",
                    subLevelName, subLevel.getUniqueId(), dest, level.dimension().location(), riders.size());

            return true;
        } catch (Exception e) {
            LOGGER.error("Sub-level teleport failed", e);
            return false;
        }
    }
}