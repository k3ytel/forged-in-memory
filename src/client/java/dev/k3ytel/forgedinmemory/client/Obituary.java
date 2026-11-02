package dev.k3ytel.forgedinmemory.client;

import java.util.Map;

import dev.k3ytel.forgedinmemory.core.ItemRecord;

/** Writes the human side of an item's life: how it was born, how it died, its headline stat and its epitaph. */
public final class Obituary {
	public enum Tone {
		AUTO, RESPECTFUL, EPIC, ROAST
	}


	private Obituary() {
	}

	public static String born(ItemRecord r) {
		String how = r.how == null ? "" : r.how;
		String by = r.maker != null ? " by " + r.maker : "";
		String text;
		if (how.equals("crafted")) text = "Crafted" + by;
		else if (how.equals("traded")) text = "Bought from a villager";
		else if (how.equals("fished")) text = "Fished up";
		else if (how.equals("already_owned")) text = "Already yours when the archive began";
		else if (how.equals("twin")) text = "Appeared as a copy of another item";
		else if (how.equals("other_world")) text = "Arrived from another world";
		else if (how.startsWith("container:")) text = "Found in " + Names.article(how.substring(10).replace('_', ' '));
		else if (how.startsWith("mob_drop:")) text = "Dropped by " + Names.article(Names.entity(how.substring(9)));
		else text = "Picked up";
		return text + " on day " + r.bornDay + " in " + Names.dim(r.bornDim);
	}

	/** One short line for the toast and the tree tooltip. */
	public static String headline(ItemRecord r) {
		long mined = r.totalMined();
		long kills = r.totalKills();
		if (mined >= kills && mined > 0) return Names.num(mined) + (mined == 1 ? " block" : " blocks");
		if (kills > 0) return Names.num(kills) + (kills == 1 ? " kill" : " kills");
		if (r.stat("absorbed") > 0) return Names.num(r.stat("absorbed") / 2) + " hearts absorbed";
		if (r.stat("blocked") > 0) return Names.num(r.stat("blocked")) + " attacks blocked";
		if (r.stat("flightDist") > 0) return Names.num(r.stat("flightDist")) + " blocks flown";
		if (!r.caught.isEmpty()) {
			long c = 0;
			for (long v : r.caught.values()) c += v;
			return Names.num(c) + " catches";
		}
		if (r.stat("durabilityUsed") > 0) return Names.num(r.stat("durabilityUsed")) + " durability used";
		return "barely used";
	}

	public static String causeLine(ItemRecord r) {
		ItemRecord.Death d = r.death;
		if (d == null) return "";
		String target = d.target;
		return switch (d.cause) {
			case "broke" -> {
				if ("mining".equals(d.action)) yield "Broke while mining " + Names.block(target) + ".";
				if ("fighting".equals(d.action)) yield "Broke in a fight with " + Names.article(Names.entity(target)) + ".";
				if ("hit_by".equals(d.action)) yield "Broke taking a hit from " + Names.article(Names.entity(target)) + ".";
				if ("flying".equals(d.action)) yield "Wore out in flight.";
				yield "Wore out and broke.";
			}
			case "lava" -> "Burned up in lava.";
			case "fire" -> "Burned in a fire.";
			case "cactus" -> "Pricked apart by a cactus.";
			case "explosion" -> target != null ? "Blown up by " + Names.article(Names.entity(target)) + "." : "Blown up.";
			case "lightning" -> "Struck by lightning.";
			case "void" -> "Fell into the void.";
			case "despawned" -> d.whileMissing ? "Vanished before " + d.ownerName + " could get back to it." : "Left on the ground until it vanished.";
			case "lost" -> "Never recovered after " + d.ownerName + " died.";
			case "sacrificed" -> "Gave its life in an anvil to make " + intoName(d.into) + " stronger.";
			case "merged" -> "Merged with another in a crafting grid.";
			default -> "Destroyed.";
		};
	}

	private static String intoName(String id) {
		ItemRecord into = ClientArchive.get(id);
		return into != null ? into.name : "another item";
	}

	public static Tone toneFor(ItemRecord r) {
		Tone chosen = Tone.valueOf(dev.k3ytel.forgedinmemory.core.FimConfig.get().tone.name());
		if (chosen != Tone.AUTO) return chosen;
		ItemRecord.Death d = r.death;
		if (d == null) return Tone.RESPECTFUL;
		boolean stupid = switch (d.cause) {
			case "lava", "fire", "cactus", "void", "despawned" -> true;
			default -> false;
		};
		if (stupid) return Tone.ROAST;
		if ("broke".equals(d.cause) && ("fighting".equals(d.action) || "hit_by".equals(d.action))) return Tone.EPIC;
		if (r.totalKills() >= 50 || r.stat("bosses") > 0) return Tone.EPIC;
		return Tone.RESPECTFUL;
	}

	public static String epitaph(ItemRecord r) {
		ItemRecord.Death d = r.death;
		if (d == null) return "";
		Tone t = toneFor(r);
		int pick = Math.floorMod(r.id.hashCode(), 3);
		boolean mending = r.enchants.containsKey("minecraft:mending");
		String owner = d.ownerName != null ? d.ownerName : "its owner";
		long kills = r.totalKills();
		String topBlock = top(r.mined);
		String topMob = top(r.kills);
		switch (t) {
			case EPIC -> {
				if (kills > 0 && topMob != null) {
					return pick(pick,
							"It took " + Names.num(kills) + " foes with it. The " + plural(Names.entity(topMob)) + " still speak of it.",
							"Fell as it lived: in battle.",
							"Songs will be sung of the " + Names.num(kills) + " it struck down.");
				}
				if (topBlock != null) {
					return pick(pick,
							"It carved " + Names.num(r.totalMined()) + " blocks out of the world and asked for nothing.",
							"The deep remembers it.",
							"Mountains moved because of it.");
				}
				return "It stood its ground to the very end.";
			}
			case ROAST -> {
				String extra = mending ? " It had Mending." : "";
				return switch (d.cause) {
					case "lava" -> pick(pick, "Thrown into lava by " + owner + "." + extra, "It deserved better. It got lava." + extra, "Lava: 1, " + owner + "'s planning: 0." + extra);
					case "fire" -> "Burned. On purpose? We may never know." + extra;
					case "cactus" -> "Hugged a cactus. The cactus won." + extra;
					case "void" -> "Still falling, probably." + extra;
					case "despawned" -> pick(pick, "Forgotten on the floor like an old sock." + extra, owner + " just walked away." + extra, "Five minutes. That's all it needed." + extra);
					default -> "It saw everything. It forgave nothing." + extra;
				};
			}
			default -> {
				if ("sacrificed".equals(d.cause) || "merged".equals(d.cause)) return "It lives on in another.";
				if (topBlock != null) {
					return pick(pick,
							"Worn down to nothing, mining " + Names.block(topBlock) + " to the very end.",
							"Every block it broke was for someone else.",
							"Rest now. The mine will wait.");
				}
				if (r.stat("absorbed") > 0) return "It took the hits so " + owner + " didn't have to.";
				return pick(pick, "Gone, but not forged-otten.", "It did its job. That's all anyone can ask.", "Rest in peace.");
			}
		}
	}

	private static String pick(int i, String a, String b, String c) {
		return i == 0 ? a : i == 1 ? b : c;
	}

	private static String plural(String s) {
		return s.endsWith("s") ? s : s + "s";
	}

	public static String top(Map<String, Long> m) {
		String best = null;
		long bv = 0;
		for (Map.Entry<String, Long> e : m.entrySet()) {
			if (e.getValue() > bv) {
				bv = e.getValue();
				best = e.getKey();
			}
		}
		return best;
	}
}
