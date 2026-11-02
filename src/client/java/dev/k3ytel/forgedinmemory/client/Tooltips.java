package dev.k3ytel.forgedinmemory.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.k3ytel.forgedinmemory.core.Ids;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** "Age 3 days · 4,812 blocks · 37 kills" under tracked items; hold Shift for more. */
public final class Tooltips {

	private Tooltips() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
			var mode = dev.k3ytel.forgedinmemory.core.FimConfig.get().tooltip;
			if (mode == dev.k3ytel.forgedinmemory.core.FimConfig.TooltipMode.OFF || !Ids.trackable(stack)) return;
			ItemRecord r = ClientArchive.get(Ids.get(stack));
			if (r == null) return;
			lines.add(Component.literal(summary(r)).withStyle(ChatFormatting.GRAY));
			if (mode != dev.k3ytel.forgedinmemory.core.FimConfig.TooltipMode.DETAILED && !Minecraft.getInstance().hasShiftDown()) {
				lines.add(Component.literal("Hold Shift for its life story").withStyle(ChatFormatting.DARK_GRAY));
				return;
			}
			for (String s : details(r)) lines.add(Component.literal(s).withStyle(ChatFormatting.DARK_GRAY));
		});
	}

	public static String summary(ItemRecord r) {
		StringBuilder sb = new StringBuilder("Age ").append(Names.age(r));
		long mined = r.totalMined();
		long kills = r.totalKills();
		if (mined > 0) sb.append(" · ").append(Names.num(mined)).append(mined == 1 ? " block" : " blocks");
		if (kills > 0) sb.append(" · ").append(Names.num(kills)).append(kills == 1 ? " kill" : " kills");
		if (mined == 0 && kills == 0) sb.append(" · ").append(Obituary.headline(r));
		if (r.bestEffort) sb.append(" · best effort");
		return sb.toString();
	}

	public static List<String> details(ItemRecord r) {
		List<String> out = new ArrayList<>();
		out.add(Titles.full(r));
		out.add(Obituary.born(r));
		top(out, "Mined", r.mined, true);
		top(out, "Killed", r.kills, false);
		if (r.stat("damageDealt") > 0) out.add("Damage dealt: " + Names.num(r.stat("damageDealt") / 2) + " hearts (biggest hit " + Names.num(r.stat("biggestHit") / 2) + ")");
		if (r.stat("absorbed") > 0) out.add("Absorbed " + Names.num(r.stat("absorbed") / 2) + " hearts over " + Names.num(r.stat("hitsTaken")) + " hits");
		if (r.stat("blocked") > 0) out.add("Blocked " + Names.num(r.stat("blocked")) + " attacks");
		if (r.stat("shots") > 0) out.add("Shots " + Names.num(r.stat("shots")) + ", hits " + Names.num(r.stat("hits")) + ", longest " + Names.num(r.stat("longestShot")) + " blocks");
		if (r.stat("flightDist") > 0) out.add("Flew " + Names.num(r.stat("flightDist")) + " blocks in " + Names.duration(r.stat("flightSec")));
		if (r.stat("casts") > 0) out.add("Casts " + Names.num(r.stat("casts")));
		if (r.stat("repairsAnvil") > 0 || r.stat("mendingRepaired") > 0) {
			out.add("Repairs: " + Names.num(r.stat("repairsAnvil")) + " at an anvil, " + Names.num(r.stat("mendingRepaired")) + " durability from Mending");
		}
		if (r.stat("distance") > 0) out.add("Travelled " + Names.num(r.stat("distance")) + " blocks with you");
		if (!r.parents.isEmpty()) out.add(r.parents.size() + (r.parents.size() == 1 ? " link" : " links") + " in the family tree");
		return out;
	}

	private static void top(List<String> out, String label, Map<String, Long> m, boolean blocks) {
		if (m.isEmpty()) return;
		List<Map.Entry<String, Long>> l = new ArrayList<>(m.entrySet());
		l.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		StringBuilder sb = new StringBuilder(label).append(": ");
		for (int i = 0; i < Math.min(3, l.size()); i++) {
			if (i > 0) sb.append(", ");
			String n = blocks ? Names.block(l.get(i).getKey()) : Names.entity(l.get(i).getKey());
			sb.append(Names.num(l.get(i).getValue())).append(' ').append(n);
		}
		if (l.size() > 3) sb.append(", +").append(l.size() - 3).append(" more");
		out.add(sb.toString());
	}
}
