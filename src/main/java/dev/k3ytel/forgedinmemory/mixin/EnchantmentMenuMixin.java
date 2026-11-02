package dev.k3ytel.forgedinmemory.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.k3ytel.forgedinmemory.server.Tracker;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;

/** Enchanting table: counts levels spent on an item. */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuMixin {
	@Shadow
	@Final
	private Container enchantSlots;

	@Inject(method = "clickMenuButton", at = @At("RETURN"))
	private void fim$enchanted(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
		if (player.level().isClientSide() || !Boolean.TRUE.equals(cir.getReturnValue())) return;
		Tracker.onEnchantTable(player, enchantSlots.getItem(0), id + 1);
	}
}
