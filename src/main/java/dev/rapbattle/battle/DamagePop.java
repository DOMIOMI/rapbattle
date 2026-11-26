package dev.rapbattle.battle;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** "−3" floating up over whoever just took a hit in a battle, gone in a second. */
public final class DamagePop {
	private DamagePop() {}

	private record Pop(DisplayEntity.TextDisplayEntity display, long until) {}

	private static final List<Pop> POPS = new ArrayList<>();

	public static void spawn(ServerWorld w, Entity target, String text, int color) {
		if (target == null) return;
		DisplayEntity.TextDisplayEntity d = EntityType.TEXT_DISPLAY.create(w, SpawnReason.COMMAND);
		if (d == null) return;
		d.setText(Text.literal(text).styled(s -> s.withColor(color).withBold(true)));
		d.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
		d.setBackground(0);
		// A little to the side, so a series of hits does not stack on one spot.
		double side = (w.random.nextDouble() - 0.5) * 0.8;
		d.refreshPositionAndAngles(target.getX() + side, target.getY() + target.getHeight() + 0.3, target.getZ() + side * 0.5, 0, 0);
		w.spawnEntity(d);
		POPS.add(new Pop(d, dev.rapbattle.Clock.now() + 1100));
	}

	/** Server tick: float up, then vanish. */
	static void tick() {
		long now = dev.rapbattle.Clock.now();
		POPS.removeIf(p -> {
			if (now > p.until() || p.display().isRemoved()) {
				p.display().discard();
				return true;
			}
			var d = p.display();
			d.setPosition(d.getX(), d.getY() + 0.04, d.getZ());
			return false;
		});
	}

	public static void clearAll() {
		for (Pop p : POPS) p.display().discard();
		POPS.clear();
	}
}
