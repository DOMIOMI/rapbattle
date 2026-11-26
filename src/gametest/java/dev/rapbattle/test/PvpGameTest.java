package dev.rapbattle.test;

import com.mojang.authlib.GameProfile;
import dev.rapbattle.battle.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;

import java.util.UUID;

import static dev.rapbattle.test.GameTestSupport.assertTrue;

/** Server-backed PvP battle gametest using a Fabric FakePlayer as the second participant. */
public class PvpGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		GameTestSupport.installKeyAudit();
		ctx.getInput().resizeWindow(1280, 720);
		ctx.waitTicks(5);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getClientWorld().waitForChunksRender();
			GameTestSupport.applyLang(ctx);

			PvpBattle[] pbRef = new PvpBattle[1];
			net.minecraft.entity.mob.ZombieEntity[] zRef = new net.minecraft.entity.mob.ZombieEntity[1];
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
				ServerWorld w = p1.getEntityWorld();
				p1.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
				p1.setInvulnerable(false);
				p1.setHealth(p1.getMaxHealth());
				ServerPlayerEntity p2 = FakePlayer.get(w, new GameProfile(UUID.randomUUID(), "RivalMC"));
				p2.refreshPositionAndAngles(p1.getX() + 4, p1.getY(), p1.getZ(), 90, 0);

				// Spawn a hostile zombie nearby to verify PvP mob truce protects players from damage.
				net.minecraft.entity.mob.ZombieEntity z = net.minecraft.entity.EntityType.ZOMBIE.create(w, net.minecraft.entity.SpawnReason.COMMAND);
				z.refreshPositionAndAngles(p1.getX() + 2, p1.getY(), p1.getZ(), 90, 0);
				z.setTarget(p1);
				w.spawnEntity(z);
				zRef[0] = z;

				// Give p1 a trophy mic in offhand and p2 gang gear so both outfit scores and perks are active.
				ItemStack mic = new ItemStack(Items.LIGHTNING_ROD);
				NbtCompound tag = new NbtCompound();
				tag.putString(Trophies.NBT_KEY, "ender_dragon");
				mic.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
				p1.setStackInHand(Hand.OFF_HAND, mic);
				assertTrue(Trophies.equippedPerk(p1) == Trophies.TrophyPerk.ENDER_DRAGON, "Offhand perk must resolve for PvP player");

				Gangs.Gang g = Gangs.of(MobProfiles.of(net.minecraft.entity.EntityType.ZOMBIE));
				p2.equipStack(EquipmentSlot.HEAD, Gangs.piece(g, 0));
				p2.equipStack(EquipmentSlot.CHEST, Gangs.piece(g, 1));
				p2.equipStack(EquipmentSlot.LEGS, Gangs.piece(g, 2));
				p2.equipStack(EquipmentSlot.FEET, Gangs.piece(g, 3));
				assertTrue(Outfit.of(p2, "en_us").level() >= 3, "Opponent outfit level must reflect gang gear");

				assertTrue(PvpBattle.challenge(p1, p2), "PvP challenge should succeed");
				int inviteId = PvpBattle.pendingInviteIdForTest(p2.getUuid());
				assertTrue(inviteId > 0, "Pending PvP invite must exist for target player");
				assertTrue(PvpBattle.answer(p2, inviteId, true), "Accepting PvP invite should start battle");
				pbRef[0] = PvpBattle.byPlayer(p1.getUuid());
				assertTrue(pbRef[0] != null, "PvP battle must be active after accept");
				pbRef[0].setTestPeerForTest(p2);
				var snap = pbRef[0].snapshotForTest(p1);
				assertTrue(snap.pvp && snap.team != null && snap.team.size() == 2
					&& snap.team.get(0).outfit != null && !snap.team.get(0).outfit.isEmpty()
					&& snap.team.get(1).outfit != null && !snap.team.get(1).outfit.isEmpty(),
					"Both players must see each other's outfit/style in PvP battle view");
			});
			ctx.waitTicks(60);
			sp.getServer().runOnServer(server -> {
				ServerPlayerEntity p1 = server.getPlayerManager().getPlayerList().get(0);
				assertTrue(p1.getHealth() >= p1.getMaxHealth(), "Hostile zombie must NOT damage PvP player during battle");
				assertTrue(Crowd.inTruce(zRef[0]), "Hostile zombie near PvP stage must be in truce");
				if (pbRef[0] != null) pbRef[0].forceEndForTest();
				assertTrue(PvpBattle.byPlayer(p1.getUuid()) == null, "PvP battle must end cleanly");
				p1.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
				if (zRef[0] != null && !zRef[0].isRemoved()) zRef[0].discard();
			});

			GameTestSupport.assertNoRawKeys("PvpGameTest");
		}
	}
}
