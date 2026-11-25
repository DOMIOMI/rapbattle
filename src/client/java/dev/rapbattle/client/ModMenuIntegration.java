package dev.rapbattle.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** "Configure" button of the mod in Mod Menu. Mod Menu is optional: without it, use /rapbattle. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return RapSettingsScreen::new;
	}
}
