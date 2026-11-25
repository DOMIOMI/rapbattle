package dev.rapbattle.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Cigarette smoke: a thin wisp from the tip all the time, and a puff from the mouth every few seconds. */
public final class SmokeFx {
	private SmokeFx() {}

	private static final Random RND = new Random();
	private static final Map<Integer, Long> NEXT_PUFF = new HashMap<>();

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(SmokeFx::tick);
	}

	private static void tick(MinecraftClient mc) {
		if (mc.world == null || mc.isPaused() || ClientBattle.view == null) return;
		long now = System.currentTimeMillis();
		for (Entity e : mc.world.getEntities()) {
			if (!(e instanceof LivingEntity le) || !Bling.smokes(e.getId())) continue;
			if (e.squaredDistanceTo(mc.player) > 40 * 40) continue;
			double yaw = Math.toRadians(le.getHeadYaw());
			Vec3d fwd = new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
			Vec3d right = new Vec3d(-fwd.z, 0, fwd.x);
			double eye = le.getStandingEyeHeight();
			double reach = Math.max(0.35, le.getWidth() * 0.5 + 0.1);
			Vec3d mouth = le.getEntityPos().add(0, eye - 0.22, 0).add(fwd.multiply(reach)).add(right.multiply(0.1));
			Vec3d tip = mouth.add(fwd.multiply(0.34)).add(right.multiply(0.08));
			// The wisp: a thin curl of smoke rising from the ember.
			if (RND.nextInt(6) == 0) {
				mc.world.addParticleClient(ParticleTypes.SMOKE, tip.x, tip.y + 0.04, tip.z,
					(RND.nextDouble() - 0.5) * 0.004, 0.012 + RND.nextDouble() * 0.01, (RND.nextDouble() - 0.5) * 0.004);
			}
			// The exhale: a few soft puffs out of the mouth every 4-8 seconds.
			long next = NEXT_PUFF.computeIfAbsent(e.getId(), k -> now + 2000 + RND.nextInt(4000));
			if (now >= next) {
				NEXT_PUFF.put(e.getId(), now + 4000 + RND.nextInt(4000));
				for (int i = 0; i < 5; i++) {
					mc.world.addParticleClient(ParticleTypes.WHITE_SMOKE, mouth.x, mouth.y, mouth.z,
						fwd.x * 0.03 + (RND.nextDouble() - 0.5) * 0.01, 0.01 + RND.nextDouble() * 0.01, fwd.z * 0.03 + (RND.nextDouble() - 0.5) * 0.01);
				}
			}
		}
		if (NEXT_PUFF.size() > 200) NEXT_PUFF.clear();
	}
}
