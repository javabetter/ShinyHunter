package com.shinyhunter;

import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gizmos.CuboidGizmo;
import net.minecraft.gizmos.Gizmo;
import net.minecraft.gizmos.GizmoPrimitives;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Shows where a thrown Critter Capsule will land, and colours the mob it would hit.
 *
 * <p>Never aims or throws: it only draws, from wherever you're already looking. The arc is
 * Sparkling Mutuals' fitted capsule curve ({@code CapsulePathModel}, CC0), measured from recorded
 * throws: from the eye, {@code p(n) = eye + SPEED·n·look + (0, ½·G·n(n−1), 0)}. {@code n} counts
 * the capsule's position updates, not seconds. SM checked it for a standing player looking from
 * 10° up to 1° down, out to 15 blocks; further out, steeper or on the move it's the same curve
 * extended, so treat long lobs as close rather than exact.
 *
 * <p>The arc is walked in short steps. Each step is checked against block collision shapes and
 * against mobs nearby (anything this mod outlines, plus living mobs that aren't players or
 * nameplate stands), and stops at whichever it meets first. Fluids don't stop it.
 */
public final class CapsulePath {

    static final double SPEED = 0.8482482249942948;
    static final double GRAVITY = -0.020085553311180723;

    /** Arc parameter per step: under half a block of flight. */
    private static final double STEP = 0.5;
    /** The arc isn't drawn for its first two updates, which start inside your head. */
    private static final double DRAW_FROM = 2.0;
    private static final double MAX_LENGTH = 64.0;
    private static final double MAX_PARAMETER = 128.0;
    private static final double TARGET_RANGE = 72.0;
    /** Smallest box a target gets, so a flat display body can still be hit. */
    private static final double MIN_TARGET_SIZE = 0.6;
    private static final int LIFETIME_MILLIS = 60;

    private record Result(List<Vec3> points, Vec3 end, int entityId, boolean landed) {
    }

    /** The mob the arc meets right now, or -1. Read by {@link EntityHighlighter} for its colour. */
    private static volatile int hitId = -1;
    private static Vec3 cachedEye;
    private static Vec3 cachedLook;
    private static Result cached;

    private CapsulePath() {
    }

    public static void onClientTick(Minecraft client) {
        ShinyConfig config = ShinyConfig.get();
        if (!config.capsulePath || client.level == null || client.player == null
                || !holdingCapsule(client.player) || !SafariBiomes.playerInSafari(client)) {
            hitId = -1;
            cached = null;
            return;
        }
        try {
            // Re-emitted every tick; the gizmo itself recomputes per frame from where you look.
            Shaders.place(Gizmos.addGizmo(new ArcGizmo()).persistForMillis(LIFETIME_MILLIS), false);
        } catch (IllegalStateException e) {
            ShinyHunterClient.LOGGER.debug("Can't draw the capsule path", e);
        }
    }

    /** The outline colour for a mob: the hit colour while the arc meets it, else its own. */
    public static int strokeFor(int entityId, int stroke) {
        if (entityId != hitId) {
            return stroke;
        }
        return BeeNestHighlighter.parseColor(ShinyConfig.get().capsuleHitColor, 0xFFFF40FF);
    }

    static boolean holdingCapsule(Player player) {
        CustomData data = player.getMainHandItem().get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }
        String id = data.copyTag().getStringOr("id", "");
        return id.equals("CRITTER_CAPSULE") || id.equals("MASTERFUL_CRITTER_CAPSULE");
    }

    static Vec3 point(Vec3 eye, Vec3 dir, double n) {
        return new Vec3(eye.x + SPEED * n * dir.x,
                eye.y + SPEED * n * dir.y + 0.5 * GRAVITY * n * (n - 1),
                eye.z + SPEED * n * dir.z);
    }

    // ------------------------------------------------------------------ tracing

    private static Result trace(Minecraft client, Vec3 eye, Vec3 look) {
        if (cached != null && eye.equals(cachedEye) && look.equals(cachedLook)) {
            return cached;
        }
        Vec3 dir = look.normalize();
        List<Entity> targets = targets(client, eye);
        List<Vec3> points = new ArrayList<>();
        Vec3 prev = eye;
        double length = 0;
        Result result = null;
        for (double n = STEP; n <= MAX_PARAMETER; n += STEP) {
            Vec3 next = point(eye, dir, n);
            double segment = prev.distanceTo(next);
            if (n - STEP >= DRAW_FROM && points.isEmpty()) {
                points.add(prev);
            }

            double bestFraction = Double.MAX_VALUE;
            Vec3 bestAt = null;
            int bestId = -1;
            BlockHitResult block = client.level.clip(new ClipContext(prev, next,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player));
            if (block.getType() != HitResult.Type.MISS) {
                bestFraction = prev.distanceTo(block.getLocation()) / segment;
                bestAt = block.getLocation();
            }
            for (Entity target : targets) {
                AABB box = EntityBoxGizmo.atLeast(target.getBoundingBox(), MIN_TARGET_SIZE);
                Optional<Vec3> hit = box.contains(prev) ? Optional.of(prev) : box.clip(prev, next);
                if (hit.isPresent()) {
                    double fraction = prev.distanceTo(hit.get()) / segment;
                    if (fraction < bestFraction) {
                        bestFraction = fraction;
                        bestAt = hit.get();
                        bestId = target.getId();
                    }
                }
            }
            if (bestAt != null) {
                points.add(bestAt);
                result = new Result(points, bestAt, bestId, true);
                break;
            }
            if (n >= DRAW_FROM) {
                points.add(next);
            }
            length += segment;
            prev = next;
            if (length >= MAX_LENGTH || next.y < client.level.getMinY()) {
                result = new Result(points, next, -1, false);
                break;
            }
        }
        if (result == null) {
            result = new Result(points, prev, -1, false);
        }
        cachedEye = eye;
        cachedLook = look;
        cached = result;
        hitId = result.entityId();
        return result;
    }

    /** What a capsule can hit: outlined critters, and living mobs that aren't players or nameplates. */
    private static List<Entity> targets(Minecraft client, Vec3 eye) {
        List<Entity> targets = new ArrayList<>();
        for (Entity entity : client.level.getEntities(client.player, new AABB(eye, eye).inflate(TARGET_RANGE))) {
            if (entity.isRemoved() || entity instanceof Player) {
                continue;
            }
            if (EntityHighlighter.isMatched(entity.getId())
                    || (entity instanceof LivingEntity && !(entity instanceof ArmorStand))) {
                targets.add(entity);
            }
        }
        return targets;
    }

    // ------------------------------------------------------------------ drawing

    /** Recomputed every frame from the interpolated view, so the arc turns with the camera. */
    private record ArcGizmo() implements Gizmo {
        @Override
        public void emit(GizmoPrimitives primitives, float alpha) {
            Minecraft client = Minecraft.getInstance();
            if (client.level == null || client.player == null) {
                return;
            }
            float partialTick = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            Result result = trace(client, client.player.getEyePosition(partialTick),
                    client.player.getViewVector(partialTick));
            ShinyConfig config = ShinyConfig.get();
            int pathColour = BeeNestHighlighter.parseColor(config.capsulePathColor, 0xFFFFB040);
            int hitColour = BeeNestHighlighter.parseColor(config.capsuleHitColor, 0xFFFF40FF);
            boolean onMob = result.entityId() >= 0;
            int colour = onMob ? hitColour : pathColour;

            if (config.capsulePathArc) {
                List<Vec3> points = result.points();
                for (int i = 1; i < points.size(); i++) {
                    primitives.addLine(points.get(i - 1), points.get(i), colour, 2.5f);
                }
            }
            if (result.landed()) {
                double r = 0.18;
                Vec3 at = result.end();
                new CuboidGizmo(new AABB(at.x - r, at.y - r, at.z - r, at.x + r, at.y + r, at.z + r),
                        GizmoStyle.strokeAndFill(colour, 2.0f, (colour & 0x00FFFFFF) | 0x66000000), false)
                        .emit(primitives, alpha);
            }
            // A mob the outlines don't cover still gets a box in the hit colour.
            if (onMob && !EntityHighlighter.isMatched(result.entityId())) {
                new EntityBoxGizmo(result.entityId(), 0.1, MIN_TARGET_SIZE,
                        GizmoStyle.strokeAndFill(hitColour, 2.0f, (hitColour & 0x00FFFFFF) | 0x33000000))
                        .emit(primitives, alpha);
            }
        }
    }
}
