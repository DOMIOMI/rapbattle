package dev.rapbattle;

import dev.rapbattle.battle.BattleManager;
import dev.rapbattle.net.Payloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RapBattle implements ModInitializer {
	public static final String MOD_ID = "rapbattle";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Server settings (and API keys) are created only when a server actually starts on this machine,
		// so a friend who just joins never gets a file with keys.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> RapConfig.load());
		Payloads.register();
		dev.rapbattle.battle.CrowdSounds.register();
		dev.rapbattle.battle.Theft.register();
		BattleManager.register();
	}
}
