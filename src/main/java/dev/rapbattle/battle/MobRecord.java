package dev.rapbattle.battle;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.rapbattle.Lang;
import dev.rapbattle.RapBattle;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

/**
 * A mob's own battle history and its rap name (per language), saved inside the mob, so the same sheep stays
 * "Биг Шерсть" / "Big Wool" forever. Lost when the mob dies.
 */
public record MobRecord(int wins, int losses, int streak, String name, String nameEn, java.util.List<String> memory, long lastDiscountTick) {
	public static final MobRecord EMPTY = new MobRecord(0, 0, 0, "", "", java.util.List.of(), 0L);

	/** One remembered opponent: who, how it ended for the mob, and a line of theirs. */
	public record Rival(String player, boolean mobWon, String line) {}

	public static final Codec<MobRecord> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("wins").forGetter(MobRecord::wins),
		Codec.INT.fieldOf("losses").forGetter(MobRecord::losses),
		Codec.INT.fieldOf("streak").forGetter(MobRecord::streak),
		Codec.STRING.optionalFieldOf("name", "").forGetter(MobRecord::name),
		Codec.STRING.optionalFieldOf("name_en", "").forGetter(MobRecord::nameEn),
		Codec.STRING.listOf().optionalFieldOf("memory", java.util.List.of()).forGetter(MobRecord::memory),
		Codec.LONG.optionalFieldOf("last_discount_tick", 0L).forGetter(MobRecord::lastDiscountTick)
	).apply(i, MobRecord::new));

	public static final AttachmentType<MobRecord> TYPE =
		AttachmentRegistry.createPersistent(Identifier.of(RapBattle.MOD_ID, "record"), CODEC);

	public static void init() {
		// Class load registers the attachment type.
	}

	public static MobRecord of(Entity e) {
		MobRecord r = e.getAttached(TYPE);
		return r == null ? EMPTY : r;
	}

	/** Fast check for hot mixins: avoids reading/decoding attachment if none is attached. */
	public static boolean hasWins(Entity e) {
		if (e == null || !e.hasAttached(TYPE)) return false;
		MobRecord r = e.getAttached(TYPE);
		return r != null && r.wins > 0;
	}

	/** Saved rap name in the given language, or empty string if not assigned yet. */
	public String name(String lang) {
		return Lang.isRu(lang) ? name : nameEn;
	}

	public static void setName(Entity e, String name, String lang) {
		MobRecord r = of(e);
		String ru = Lang.isRu(lang) ? name : r.name;
		String en = Lang.isRu(lang) ? r.nameEn : name;
		e.setAttached(TYPE, new MobRecord(r.wins, r.losses, r.streak, ru, en, r.memory, r.lastDiscountTick));
	}

	@Deprecated
	public static void setName(Entity e, String name) {
		setName(e, name, Lang.RU);
	}

	public static void win(Entity e) {
		MobRecord r = of(e);
		e.setAttached(TYPE, new MobRecord(r.wins + 1, r.losses, Math.max(0, r.streak) + 1, r.name, r.nameEn, r.memory, r.lastDiscountTick));
	}

	public static void loss(Entity e) {
		MobRecord r = of(e);
		e.setAttached(TYPE, new MobRecord(r.wins, r.losses + 1, Math.min(0, r.streak) - 1, r.name, r.nameEn, r.memory, r.lastDiscountTick));
	}

	public static void markDiscount(Entity e, long worldTime) {
		MobRecord r = of(e);
		e.setAttached(TYPE, new MobRecord(r.wins, r.losses, r.streak, r.name, r.nameEn, r.memory, worldTime));
	}

	/** Remembers a battle with this player (the last 4 opponents are kept). */
	public static void remember(Entity e, String player, boolean mobWon, String line) {
		MobRecord r = of(e);
		java.util.List<String> mem = new java.util.ArrayList<>();
		for (String s : r.memory) if (!s.startsWith(player + "\t")) mem.add(s);
		String clean = line == null ? "" : line.replace('\t', ' ').replace('\n', ' ').trim();
		if (clean.length() > 90) clean = clean.substring(0, 89) + "…";
		mem.add(player + "\t" + (mobWon ? "1" : "0") + "\t" + clean);
		while (mem.size() > 4) mem.remove(0);
		e.setAttached(TYPE, new MobRecord(r.wins, r.losses, r.streak, r.name, r.nameEn, java.util.List.copyOf(mem), r.lastDiscountTick));
	}

	/** What this mob remembers about the player, or null if they never met. */
	public static Rival rival(Entity e, String player) {
		for (String s : of(e).memory) {
			String[] p = s.split("\t", 3);
			if (p.length == 3 && p[0].equals(player)) return new Rival(p[0], p[1].equals("1"), p[2]);
		}
		return null;
	}
}
