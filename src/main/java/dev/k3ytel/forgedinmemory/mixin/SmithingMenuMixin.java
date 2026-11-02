package dev.k3ytel.forgedinmemory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.k3ytel.forgedinmemory.server.Tracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;

/** Smithing upgrades: the item keeps its ID; the netherite ingot gets added to its lineage. */
@Mixin(SmithingMenu.class)
public abstract class SmithingMenuMixin {
	@Inject(method = "onTake", at = @At("HEAD"))
	private void fim$onTake(Player player, ItemStack result, CallbackInfo ci) {
		if (player.level().isClientSide()) return;
		Tracker.onSmith(player, ((ItemCombinerMenuAccessor) this).fim$inputSlots(), result);
	}
}
