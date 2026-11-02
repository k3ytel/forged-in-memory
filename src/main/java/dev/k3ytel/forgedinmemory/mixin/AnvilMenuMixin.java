package dev.k3ytel.forgedinmemory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.k3ytel.forgedinmemory.server.Tracker;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;

/** Anvil: sacrifices (two items merged), repairs with materials. */
@Mixin(AnvilMenu.class)
public abstract class AnvilMenuMixin {
	@Shadow
	private int repairItemCountCost;

	@Inject(method = "onTake", at = @At("HEAD"))
	private void fim$onTake(Player player, ItemStack result, CallbackInfo ci) {
		if (player.level().isClientSide()) return;
		Tracker.onAnvil(player, ((ItemCombinerMenuAccessor) this).fim$inputSlots(), result, repairItemCountCost, ((AnvilMenu) (Object) this).getCost());
	}
}
