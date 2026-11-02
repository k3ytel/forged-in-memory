package dev.k3ytel.forgedinmemory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.k3ytel.forgedinmemory.server.Tracker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Remembers what hurt a dropped item, and notices pickups (for birth origin + material ledger). */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
	@Shadow
	public abstract ItemStack getItem();

	@Unique
	private ItemStack fim$before = ItemStack.EMPTY;
	@Unique
	private int fim$countBefore;

	@Inject(method = "hurtServer", at = @At("HEAD"))
	private void fim$hurt(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
		Tracker.onItemEntityHurt((ItemEntity) (Object) this, source);
	}

	@Inject(method = "playerTouch", at = @At("HEAD"))
	private void fim$touchHead(Player player, CallbackInfo ci) {
		if (player.level().isClientSide() || !Tracker.active()) return;
		ItemStack s = getItem();
		fim$before = s.copy();
		fim$countBefore = s.getCount();
	}

	/**
	 * Runs right after Inventory.add succeeded (at Player.take). Must be here and not at TAIL:
	 * vanilla puts the count back on the emptied stack afterwards for the pickup animation.
	 */
	@Inject(method = "playerTouch", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;take(Lnet/minecraft/world/entity/Entity;I)V"))
	private void fim$touchTaken(Player player, CallbackInfo ci) {
		if (player.level().isClientSide() || !Tracker.active() || fim$before.isEmpty()) return;
		int picked = fim$countBefore - getItem().getCount();
		Tracker.onPickup((ItemEntity) (Object) this, player, fim$before, picked);
		fim$before = ItemStack.EMPTY;
	}
}
