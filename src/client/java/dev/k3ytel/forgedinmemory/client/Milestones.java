package dev.k3ytel.forgedinmemory.client;

import dev.k3ytel.forgedinmemory.core.ItemRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/** Toasts when an item crosses a round number: "Diamond Pickaxe: 1,000 blocks mined!" */
public final class Milestones {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(5000L);

	private Milestones() {
	}

	private interface Metric {
		double get(ItemRecord r);
	}

	private record Check(Metric metric, String what, double[] steps) {
	}

	private static final Check[] CHECKS = {
			new Check(ItemRecord::totalMined, "blocks mined", new double[] {100, 500, 1000, 2500, 5000, 10000, 25000, 50000, 100000}),
			new Check(ItemRecord::totalKills, "kills", new double[] {10, 50, 100, 250, 500, 1000, 5000}),
			new Check(r -> r.stat("diamonds"), "diamonds found", new double[] {1, 10, 50, 100, 500}),
			new Check(r -> r.stat("debris"), "ancient debris found", new double[] {1, 4, 16, 64}),
			new Check(r -> r.stat("bosses"), "bosses slain", new double[] {1, 5, 10}),
			new Check(r -> r.stat("absorbed") / 2, "hearts of damage absorbed", new double[] {50, 250, 1000, 5000}),
			new Check(r -> r.stat("blocked"), "attacks blocked", new double[] {50, 250, 1000}),
			new Check(r -> r.stat("distance"), "blocks travelled", new double[] {1000, 10000, 100000, 1000000}),
			new Check(r -> r.stat("flightDist"), "blocks flown", new double[] {1000, 10000, 100000, 1000000}),
			new Check(r -> r.stat("fish"), "fish caught", new double[] {10, 100, 500}),
			new Check(r -> r.stat("crops"), "crops harvested", new double[] {100, 1000, 10000}),
			new Check(r -> r.stat("hits"), "arrows on target", new double[] {50, 250, 1000}),
	};

	/** Compare the old and new copy of a record and toast the highest milestone crossed. */
	public static void check(ItemRecord before, ItemRecord after) {
		dev.k3ytel.forgedinmemory.core.FimConfig cfg = dev.k3ytel.forgedinmemory.core.FimConfig.get();
		if (!cfg.milestones || before == null || after == null || after.isDead()) return;
		String best = null;
		for (Check c : CHECKS) {
			double a = c.metric.get(before), b = c.metric.get(after);
			for (double step : c.steps) {
				if (a < step && b >= step) best = Names.num(step) + " " + c.what + "!";
			}
		}
		if (best == null) return;
		Minecraft mc = Minecraft.getInstance();
		SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.literal("✦ " + after.name), Component.literal(best));
		if (cfg.milestoneChime) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 0.9f));
	}
}
