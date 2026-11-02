package dev.k3ytel.forgedinmemory.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.k3ytel.forgedinmemory.client.gui.TreeScreen;
import dev.k3ytel.forgedinmemory.client.mixin.ContainerScreenAccessor;
import dev.k3ytel.forgedinmemory.net.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class ForgedInMemoryClient implements ClientModInitializer {
	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("forged-in-memory", "main"));
	public static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(Net.SyncPayload.TYPE, (payload, ctx) -> ClientArchive.onSync(payload));
		ClientPlayNetworking.registerGlobalReceiver(Net.DeathPayload.TYPE, (payload, ctx) -> DeathFx.onDeath(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(ClientArchive::clear));
		DeathFx.register();
		Tooltips.register();

		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.forged-in-memory.open", InputConstants.Type.KEYBOARD, InputConstants.KEY_HOME, CATEGORY));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) {
				if (mc.gui.screen() == null && mc.player != null) open(mc);
			}
		});

		// Small star button next to the survival inventory.
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!(screen instanceof InventoryScreen inv) || !dev.k3ytel.forgedinmemory.core.FimConfig.get().inventoryButton) return;
			Button b = Button.builder(Component.literal("✦"), btn -> open(mc))
					.tooltip(Tooltip.create(Component.literal("Forged in Memory: your gear's family tree")))
					.bounds(0, 0, 18, 18).build();
			Screens.getWidgets(screen).add(b);
			ContainerScreenAccessor acc = (ContainerScreenAccessor) inv;
			ScreenEvents.beforeExtract(screen).register((s, g, mx, my, delta) -> {
				b.setX(acc.fim$leftPos() + 176 + 2);
				b.setY(acc.fim$topPos() + 2);
			});
		});
	}

	public static void open(Minecraft mc) {
		mc.gui.setScreen(new TreeScreen(mc.gui.screen()));
	}
}
