package dev.k3ytel.forgedinmemory.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.k3ytel.forgedinmemory.server.Tracker;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Durability use + breaking, and dropped items being destroyed (lava, fire, cactus, explosions). */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Inject(method = "applyDamage(ILnet/minecraft/server/level/ServerPlayer;Ljava/util/function/Consumer;)V", at = @At("HEAD"))
	private void fim$applyDamage(int newDamage, ServerPlayer player, Consumer<ItemStack> onBreak, CallbackInfo ci) {
		Tracker.onDurability((ItemStack) (Object) this, newDamage, player);
	}

	@Inject(method = "onDestroyed", at = @At("HEAD"))
	private void fim$onDestroyed(ItemEntity entity, CallbackInfo ci) {
		if (!entity.level().isClientSide()) Tracker.onItemDestroyed((ItemStack) (Object) this, entity);
	}
}
