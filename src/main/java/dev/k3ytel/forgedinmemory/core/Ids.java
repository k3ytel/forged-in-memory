package dev.k3ytel.forgedinmemory.core;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/** The hidden item ID (stored in the item's custom data) and item classification. */
public final class Ids {
	public static final String KEY = "forged_in_memory:id";

	private Ids() {
	}

	public static boolean trackable(ItemStack s) {
		return s != null && !s.isEmpty() && s.isDamageableItem();
	}

	public static String get(ItemStack s) {
		if (s == null || s.isEmpty()) return null;
		CustomData d = s.get(DataComponents.CUSTOM_DATA);
		if (d == null || d.isEmpty()) return null;
		return d.copyTag().getString(KEY).orElse(null);
	}

	public static void set(ItemStack s, String id) {
		CustomData.update(DataComponents.CUSTOM_DATA, s, tag -> tag.putString(KEY, id));
	}

	public static String newId() {
		long v = ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFFL;
		return String.format("%012x", v);
	}

	public static String itemKey(Item item) {
		return BuiltInRegistries.ITEM.getKey(item).toString();
	}

	public static String kind(ItemStack s) {
		if (s.is(ItemTags.PICKAXES)) return "pickaxe";
		if (s.is(ItemTags.AXES)) return "axe";
		if (s.is(ItemTags.SHOVELS)) return "shovel";
		if (s.is(ItemTags.HOES)) return "hoe";
		if (s.is(ItemTags.SWORDS)) return "sword";
		if (s.is(ItemTags.SPEARS)) return "spear";
		if (s.is(Items.MACE)) return "mace";
		if (s.is(ItemTags.HEAD_ARMOR)) return "helmet";
		if (s.is(ItemTags.CHEST_ARMOR)) return "chestplate";
		if (s.is(ItemTags.LEG_ARMOR)) return "leggings";
		if (s.is(ItemTags.FOOT_ARMOR)) return "boots";
		if (s.is(Items.BOW)) return "bow";
		if (s.is(Items.CROSSBOW)) return "crossbow";
		if (s.is(Items.TRIDENT)) return "trident";
		if (s.is(Items.SHIELD)) return "shield";
		if (s.is(Items.ELYTRA)) return "elytra";
		if (s.is(Items.FISHING_ROD)) return "fishing_rod";
		if (s.is(Items.SHEARS)) return "shears";
		if (s.is(Items.FLINT_AND_STEEL)) return "flint_and_steel";
		if (s.is(Items.BRUSH)) return "brush";
		if (s.is(Items.CARROT_ON_A_STICK) || s.is(Items.WARPED_FUNGUS_ON_A_STICK)) return "on_a_stick";
		return "other";
	}

	public static boolean isArmorKind(String kind) {
		return "helmet".equals(kind) || "chestplate".equals(kind) || "leggings".equals(kind) || "boots".equals(kind);
	}

	/** Material family + how many "units" one item of it is worth, or null if not a gear material. */
	public static Family family(ItemStack s) {
		if (s == null || s.isEmpty()) return null;
		if (s.is(ItemTags.LOGS)) return new Family("wood", 4);
		if (s.is(ItemTags.PLANKS)) return new Family("wood", 1);
		String p = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
		switch (p) {
			case "stick": return new Family("wood", 0.5);
			case "cobblestone", "cobbled_deepslate", "blackstone": return new Family("stone", 1);
			case "string": return new Family("string", 1);
			case "leather": return new Family("leather", 1);
			case "rabbit_hide": return new Family("leather", 0.25);
			case "flint": return new Family("flint", 1);
			case "breeze_rod": return new Family("breeze_rod", 1);
			case "heavy_core": return new Family("heavy_core", 1);
			case "turtle_scute", "armadillo_scute": return new Family("scute", 1);
			case "phantom_membrane": return new Family("membrane", 1);
			case "ancient_debris": return new Family("netherite", 0.25);
			case "netherite_scrap": return new Family("netherite", 0.25);
			case "netherite_ingot": return new Family("netherite", 1);
			case "netherite_block": return new Family("netherite", 9);
			default: break;
		}
		for (String m : new String[] {"iron", "gold", "copper"}) {
			if (p.equals("raw_" + m) || p.equals(m + "_ingot") || p.equals(m + "_ore") || p.equals("deepslate_" + m + "_ore")) return new Family(m, 1);
			if (p.equals(m + "_nugget")) return new Family(m, 1.0 / 9);
			if (p.equals("raw_" + m + "_block") || p.equals(m + "_block")) return new Family(m, 9);
		}
		if (p.equals("nether_gold_ore")) return new Family("gold", 1);
		for (String m : new String[] {"diamond", "emerald"}) {
			if (p.equals(m) || p.equals(m + "_ore") || p.equals("deepslate_" + m + "_ore")) return new Family(m, 1);
			if (p.equals(m + "_block")) return new Family(m, 9);
		}
		return null;
	}

	public record Family(String name, double weight) {
	}
}
