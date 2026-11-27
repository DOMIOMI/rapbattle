package dev.rapbattle.net;

import dev.rapbattle.RapBattle;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** All mod packets. Battle state travels as JSON (see {@link BattleView}); mic audio as raw WAV chunks. */
public final class Payloads {
	private Payloads() {}

	/** Server → client: battle state / invite / end, as JSON. */
	public record StateS2C(String json) implements CustomPayload {
		public static final Id<StateS2C> ID = new Id<>(Identifier.of(RapBattle.MOD_ID, "state"));
		public static final PacketCodec<RegistryByteBuf, StateS2C> CODEC =
			PacketCodecs.string(1 << 20).<RegistryByteBuf>cast().xmap(StateS2C::new, StateS2C::json);
		@Override public Id<? extends CustomPayload> getId() { return ID; }
	}

	/** Client → server: small actions (invite answer, leave) as JSON. */
	public record ActionC2S(String json) implements CustomPayload {
		public static final Id<ActionC2S> ID = new Id<>(Identifier.of(RapBattle.MOD_ID, "action"));
		public static final PacketCodec<RegistryByteBuf, ActionC2S> CODEC =
			PacketCodecs.string(4096).<RegistryByteBuf>cast().xmap(ActionC2S::new, ActionC2S::json);
		@Override public Id<? extends CustomPayload> getId() { return ID; }
	}

	/**
	 * Client → server: one chunk of the player's recorded verse (16 kHz mono WAV). {@code startMs}: the battle clock at
	 * the first sample, already corrected by the player's measured audio latency (so word times map onto the beats).
	 */
	public record AudioC2S(int battleId, int seq, boolean last, int startMs, byte[] data) implements CustomPayload {
		public static final int MAX_CHUNK = 30000;
		public static final Id<AudioC2S> ID = new Id<>(Identifier.of(RapBattle.MOD_ID, "audio"));
		public static final PacketCodec<RegistryByteBuf, AudioC2S> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT, AudioC2S::battleId,
			PacketCodecs.VAR_INT, AudioC2S::seq,
			PacketCodecs.BOOLEAN, AudioC2S::last,
			PacketCodecs.VAR_INT, AudioC2S::startMs,
			PacketCodecs.byteArray(MAX_CHUNK), AudioC2S::data,
			AudioC2S::new);
		@Override public Id<? extends CustomPayload> getId() { return ID; }
	}

	/** Server → client: nearby mob level badges (entity ids, packed rank|boss bytes, status bytes). */
	public record MobBadgesS2C(java.util.List<Integer> entityIds, byte[] ranks, byte[] statuses) implements CustomPayload {
		public static final Id<MobBadgesS2C> ID = new Id<>(Identifier.of(RapBattle.MOD_ID, "mob_badges"));
		public static final PacketCodec<RegistryByteBuf, MobBadgesS2C> CODEC = PacketCodec.tuple(
			PacketCodecs.VAR_INT.collect(PacketCodecs.toList(256)), MobBadgesS2C::entityIds,
			PacketCodecs.byteArray(256), MobBadgesS2C::ranks,
			PacketCodecs.byteArray(256), MobBadgesS2C::statuses,
			MobBadgesS2C::new);
		@Override public Id<? extends CustomPayload> getId() { return ID; }
	}

	public static void register() {
		PayloadTypeRegistry.playS2C().register(StateS2C.ID, StateS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(MobBadgesS2C.ID, MobBadgesS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(ActionC2S.ID, ActionC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(AudioC2S.ID, AudioC2S.CODEC);
	}
}
