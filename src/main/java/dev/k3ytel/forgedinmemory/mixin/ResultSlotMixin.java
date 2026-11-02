package dev.k3ytel.forgedinmemory.mixin;

import java.util.ArrayList;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.k3ytel.forgedinmemory.server.Tracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;

/** Crafting: remember the ingredients (material lineage, grid repairs) before they are used up. */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
	@Shadow
	@Final
	private CraftingContainer craftSlots;

	@Inject(method = "onTake", at = @At("HEAD"))
	private void fim$onTake(Player player, ItemStack stack, CallbackInfo ci) {
		if (player.level().isClientSide()) return;
		Tracker.onCraft(player, stack, new ArrayList<>(craftSlots.getItems()));
	}
}
