package dev.k3ytel.forgedinmemory.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/** Where the inventory panel actually is (it moves when the recipe book opens). */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
	@Accessor("leftPos")
	int fim$leftPos();

	@Accessor("topPos")
	int fim$topPos();
}
