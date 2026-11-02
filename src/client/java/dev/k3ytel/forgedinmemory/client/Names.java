package dev.k3ytel.forgedinmemory.client;

import java.text.NumberFormat;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/** Turns registry ids into the names the player sees, and numbers/ages into short text. */
public final class Names {
	private static final NumberFormat NF = NumberFormat.getIntegerInstance(Locale.US);

	private Names() {
	}

	public static String block(String id) {
		Identifier i = id == null ? null : Identifier.tryParse(id);
		if (i == null) return "?";
		Block b = BuiltInRegistries.BLOCK.getValue(i);
		return b.getName().getString();
	}

	public static String entity(String id) {
		Identifier i = id == null ? null : Identifier.tryParse(id);
		if (i == null) return "?";
		if (!BuiltInRegistries.ENTITY_TYPE.containsKey(i)) return id;
		EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.getValue(i);
		return t.getDescription().getString();
	}

	public static Item item(String id) {
		Identifier i = id == null ? null : Identifier.tryParse(id);
		return i == null ? null : BuiltInRegistries.ITEM.getValue(i);
	}

	public static ItemStack stack(String id) {
		Item it = item(id);
		return it == null ? ItemStack.EMPTY : new ItemStack(it);
	}

	public static String itemName(String id) {
		ItemStack s = stack(id);
		return s.isEmpty() ? "?" : s.getHoverName().getString();
	}

	public static String enchant(String id, int level) {
		Identifier i = Identifier.tryParse(id);
		String name = i == null ? id : Component.translatable("enchantment." + i.getNamespace() + "." + i.getPath()).getString();
		return level > 1 ? name + " " + roman(level) : name;
	}

	public static String roman(int n) {
		String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
		return n >= 0 && n < r.length ? r[n] : String.valueOf(n);
	}

	public static String num(double v) {
		return NF.format(Math.round(v));
	}

	public static String dim(String id) {
		if (id == null) return "?";
		return switch (id) {
			case "minecraft:overworld" -> "the Overworld";
			case "minecraft:the_nether" -> "the Nether";
			case "minecraft:the_end" -> "the End";
			default -> id;
		};
	}

	/** Current in-game day on the client. */
	public static long today() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? 0 : mc.level.getOverworldClockTime() / 24000L;
	}

	/** "3 days", or "2 h 14 min" when it lived less than an in-game day. */
	public static String age(dev.k3ytel.forgedinmemory.core.ItemRecord r) {
		long endDay = r.death != null ? r.death.day : today();
		long days = Math.max(0, endDay - r.bornDay);
		if (days >= 1) return days == 1 ? "1 day" : days + " days";
		long endMs = r.death != null ? r.death.at : System.currentTimeMillis();
		long min = Math.max(0, (endMs - r.bornAt) / 60000L);
		if (min < 1) return "a few seconds";
		if (min < 60) return min + " min";
		return (min / 60) + " h " + (min % 60) + " min";
	}

	public static String duration(double seconds) {
		long s = Math.round(seconds);
		if (s < 60) return s + " s";
		if (s < 3600) return (s / 60) + " min";
		return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
	}

	public static String article(String noun) {
		if (noun == null || noun.isEmpty()) return "something";
		char c = Character.toLowerCase(noun.charAt(0));
		return ("aeiou".indexOf(c) >= 0 ? "an " : "a ") + noun;
	}
}
