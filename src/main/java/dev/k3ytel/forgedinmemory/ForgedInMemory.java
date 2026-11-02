package dev.k3ytel.forgedinmemory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.k3ytel.forgedinmemory.net.Net;
import dev.k3ytel.forgedinmemory.server.Tracker;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

public class ForgedInMemory implements ModInitializer {
	public static final String MOD_ID = "forged-in-memory";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		dev.k3ytel.forgedinmemory.core.FimConfig.load();
		Net.register();
		Tracker.register();
		dev.k3ytel.forgedinmemory.server.DevCommands.register();
		LOGGER.info("[Forged in Memory] Loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
