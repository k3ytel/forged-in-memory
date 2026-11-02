package dev.k3ytel.forgedinmemory.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import dev.k3ytel.forgedinmemory.core.ItemRecord;

/** The fun side of an item's life: its sign, birth, personality, nemesis, fun facts and rankings. */
public final class Story {
	private Story() {
	}

	// ---------------------------------------------------------------- zodiac

	private static final String[][] SIGNS = {
			{"The Creeper", "Quiet until it isn't. Explosive temper, short fuse."},
			{"The Golem", "Steadfast protector. Hands out flowers to those it likes."},
			{"The Enderman", "Mysterious and tall-minded. Hates being stared at."},
			{"The Villager", "Shrewd trader. Will absolutely overcharge you."},
			{"The Axolotl", "Playful, loyal, plays dead when things get tough."},
			{"The Wither", "Born to break things. Not great at parties."},
			{"The Phantom", "A night owl that punishes poor sleep habits."},
			{"The Bee", "Hard-working and sweet, until provoked."},
			{"The Allay", "Helpful to a fault. Loves music and shiny things."},
			{"The Warden", "Keeps to the dark. Very sensitive to noise."},
			{"The Fox", "Sly collector. Will steal your stuff and sleep through the day."},
			{"The Sniffer", "An old soul that digs up the past."}};

	public static String sign(ItemRecord r) {
		return SIGNS[(int) Math.floorMod(r.bornDay, (long) SIGNS.length)][0];
	}

	public static String signTrait(ItemRecord r) {
		return SIGNS[(int) Math.floorMod(r.bornDay, (long) SIGNS.length)][1];
	}

	// ---------------------------------------------------------------- birth

	private static final String[] MOONS = {"a full moon", "a waning gibbous moon", "a last-quarter moon", "a waning crescent",
			"a new moon", "a waxing crescent", "a first-quarter moon", "a waxing gibbous moon"};

	public static String birthMoment(ItemRecord r) {
		if (r.bornTimeOfDay < 0) return null;
		long t = r.bornTimeOfDay;
		String when;
		if (t < 1000) when = "at sunrise";
		else if (t < 5000) when = "in the morning";
		else if (t < 7000) when = "at noon";
		else if (t < 11500) when = "in the afternoon";
		else if (t < 13500) when = "at sunset";
		else if (t < 17000) when = "in the evening";
		else if (t < 19000) when = "at midnight";
		else if (t < 22500) when = "in the dead of night";
		else when = "just before dawn";
		boolean night = t >= 13000 && t < 23000;
		StringBuilder sb = new StringBuilder("Born ").append(when);
		if (night && r.bornMoon >= 0) sb.append(" under ").append(MOONS[r.bornMoon % 8]);
		if ("thunder".equals(r.bornWeather)) sb.append(", in the middle of a thunderstorm");
		else if ("rain".equals(r.bornWeather)) sb.append(", in the rain");
		return sb.append('.').toString();
	}

	// ---------------------------------------------------------------- personality

	public static List<String> traits(ItemRecord r) {
		List<String> t = new ArrayList<>();
		double held = r.stat("heldSec") + r.stat("wornSec");
		long days = Math.max(0, (r.death != null ? r.death.day : Names.today()) - r.bornDay);
		long kills = r.totalKills(), mined = r.totalMined();
		if (r.stat("bosses") > 0) t.add("Legendary");
		if (r.bornMoon == 0 && r.bornTimeOfDay >= 13000 && r.bornTimeOfDay < 23000) t.add("Moon child");
		if ("thunder".equals(r.bornWeather)) t.add("Storm-born");
		if (held > 600 && r.stat("nightSec") > held * 0.5) t.add("Night owl");
		if (r.stat("lavaSec") > 0 || r.stat("fireSec") > 60) t.add("Daredevil");
		if (r.stat("darkSec") > 1800 || (r.stats.containsKey("deepestY") && r.stat("deepestY") < -45)) t.add("Cave dweller");
		if (r.biomes.size() >= 12) t.add("Globetrotter");
		else if (days >= 3 && r.stat("farthestFromHome") > 0 && r.stat("farthestFromHome") < 150) t.add("Homebody");
		if (r.stat("diamonds") >= 20) t.add("Diamond hoarder");
		if (mined > 500 && r.stat("diamonds") / mined > 1 / 200.0) t.add("Lucky");
		if (kills >= 100) t.add("Bloodthirsty");
		if (r.killKinds.getOrDefault("baby", 0L) > 0) t.add("Merciless");
		if (kills == 0 && days >= 5 && !ItemKinds.isArmor(r.kind)) t.add("Pacifist");
		if (r.stat("drops") >= 5) t.add("Clumsy");
		if (r.stat("deathsWorn") + r.stat("deathsHeld") >= 3) t.add("Survivor");
		if (r.stat("savedLife") >= 1) t.add("Guardian angel");
		if (r.stat("bestMinute") >= 60) t.add("Workaholic");
		else if (days >= 3 && mined + kills < 20 && r.stat("absorbed") == 0) t.add("Lazy");
		if (r.stat("underwaterSec") > 300) t.add("Water baby");
		if (r.stat("flightDist") > 10000) t.add("Sky-born");
		double nether = r.timeIn.getOrDefault("minecraft:the_nether", 0.0), over = r.timeIn.getOrDefault("minecraft:overworld", 0.0);
		if (nether > 600 && nether > over) t.add("Nether-tempered");
		if (r.stat("jumps") > 5000) t.add("Bouncy");
		if (r.stat("trades") >= 50) t.add("Haggler");
		if (r.stat("cake") >= 10) t.add("Sweet tooth");
		if (r.killKinds.getOrDefault("revenge", 0L) >= 10) t.add("Holds grudges");
		return t;
	}

	// ---------------------------------------------------------------- favourites + nemesis

	public static String nemesis(ItemRecord r) {
		String best = null;
		double bv = 0;
		for (Map.Entry<String, Double> e : r.hitBy.entrySet()) {
			if (e.getValue() > bv) {
				bv = e.getValue();
				best = e.getKey();
			}
		}
		if (best == null) return null;
		String who = best.startsWith("dmg:") ? damageName(best.substring(4)) : Names.entity(best);
		return who + " (" + Names.num(bv / 2) + " hearts of damage)";
	}

	public static String damageName(String k) {
		return switch (k) {
			case "fall" -> "Gravity";
			case "explosion" -> "Explosions";
			case "fire" -> "Fire";
			case "projectile" -> "Arrows";
			case "drowning" -> "Water";
			case "freezing" -> "Powder snow";
			case "lightning" -> "Lightning";
			default -> "The world itself";
		};
	}

	public static String who(String key) {
		if (key == null) return "?";
		return key.startsWith("dmg:") ? damageName(key.substring(4)) : Names.entity(key);
	}

	// ---------------------------------------------------------------- death place

	public static String depth(ItemRecord.Death d) {
		if (d == null || d.pos == null || !"minecraft:overworld".equals(d.dim)) return null;
		int y = d.pos[1];
		if (y < 0) return "deep underground";
		if (y < 50) return "underground";
		if (y > 150) return "high in the sky";
		if (y > 100) return "up in the mountains";
		return null;
	}

	public static String biome(String id) {
		if (id == null || id.isEmpty()) return null;
		int c = id.indexOf(':');
		String p = (c >= 0 ? id.substring(c + 1) : id).replace('_', ' ');
		return p.isEmpty() ? null : Character.toUpperCase(p.charAt(0)) + p.substring(1);
	}

	/** "120, -54, 33 in the Overworld (Deep dark, deep underground)" */
	public static String place(ItemRecord.Death d) {
		if (d == null || d.pos == null) return null;
		StringBuilder sb = new StringBuilder().append(d.pos[0]).append(", ").append(d.pos[1]).append(", ").append(d.pos[2])
				.append(" in ").append(Names.dim(d.dim));
		List<String> extra = new ArrayList<>();
		String b = biome(d.biome);
		if (b != null) extra.add(b);
		String dp = depth(d);
		if (dp != null) extra.add(dp);
		if (!extra.isEmpty()) sb.append(" (").append(String.join(", ", extra)).append(")");
		return sb.toString();
	}

	public static double distanceFromBirth(ItemRecord r) {
		if (r.death == null || r.death.pos == null || r.bornPos == null || !r.death.dim.equals(r.bornDim)) return -1;
		return Math.hypot(r.death.pos[0] - r.bornPos[0], r.death.pos[2] - r.bornPos[2]);
	}

	public static String moment(String m) {
		String[] p = m.split("\\|");
		if (p.length < 3) return m;
		long n;
		try {
			n = Long.parseLong(p[2]);
		} catch (NumberFormatException e) {
			n = 1;
		}
		String x = n > 1 ? " ×" + n : "";
		return switch (p[0]) {
			case "mined" -> "Mined " + Names.block(p[1]) + x;
			case "killed" -> "Killed " + Names.article(Names.entity(p[1])) + x;
			case "hit" -> "Took a hit from " + who(p[1]) + x;
			case "blocked" -> "Blocked " + who(p[1]) + x;
			case "dropped" -> "Got dropped" + x;
			default -> p[0] + " " + p[1] + x;
		};
	}

	// ---------------------------------------------------------------- fun facts

	public static List<String> facts(ItemRecord r, Collection<ItemRecord> all) {
		List<String> f = new ArrayList<>();
		long mined = r.totalMined(), kills = r.totalKills();
		double held = r.stat("heldSec") + r.stat("wornSec");
		if (mined >= 384) f.add("Stacked up, every block it mined would reach from bedrock to the build limit " + fmt(mined / 384.0) + " times.");
		if (mined >= 3456) f.add("That's " + fmt(mined / 3456.0) + " double chests full of blocks.");
		if (r.stat("diamonds") > 0 && mined > 0) f.add("It found a diamond every " + Names.num(mined / r.stat("diamonds")) + " blocks.");
		if (kills > 0 && held > 60) f.add("One kill every " + Names.duration(held / kills) + " of holding it.");
		if (r.stat("absorbed") >= 20) f.add("It took enough damage to kill you " + fmt(r.stat("absorbed") / 20) + " times over.");
		double dist = r.stat("distance");
		if (dist >= 42195) f.add("It travelled " + fmt(dist / 42195) + " marathons with you.");
		else if (dist >= 1000) f.add("It travelled " + Names.num(dist) + " blocks with you, " + fmt(dist / 300) + " times the length of a big village.");
		if (dist > 0) f.add("That's " + String.format(Locale.US, "%.4f", dist / 30_000_000 * 100) + "% of the way to the world border.");
		if (r.stat("jumps") > 0) f.add("It jumped " + Names.num(r.stat("jumps")) + " times, " + fmt(r.stat("jumps") * 1.25 / 324) + " Eiffel Towers of climbing.");
		if (held > 0) f.add("Your heart beat about " + Names.num(held * 1.2) + " times while you had it.");
		if (r.stat("flightDist") > 1000) f.add("It flew " + fmt(r.stat("flightDist") / 1000) + " times the gap to the End's outer islands.");
		if (r.stat("fish") > 0) f.add("It caught enough fish to feed " + Names.num(Math.max(1, r.stat("fish") / 3)) + " stray cats.");
		if (r.stat("sunsets") > 0) f.add("It watched " + Names.num(r.stat("sunsets")) + (r.stat("sunsets") == 1 ? " sunset" : " sunsets")
				+ (r.stat("fullMoons") > 0 ? " and " + Names.num(r.stat("fullMoons")) + " full moons" : "") + " with you.");
		if (r.maxDamage > 0 && r.stat("durabilityUsed") > r.maxDamage) f.add("It wore itself out " + fmt(r.stat("durabilityUsed") / r.maxDamage) + " times over and kept going.");
		if (r.stat("biggestFall") > 0) f.add("It survived a " + Names.num(r.stat("biggestFall")) + "-block fall with you.");
		if (r.stat("farthestFromHome") > 0) f.add("The farthest it ever got from its birthplace: " + Names.num(r.stat("farthestFromHome")) + " blocks.");
		double fb = distanceFromBirth(r);
		if (fb >= 0) f.add("It died " + Names.num(fb) + " blocks from where it was born.");
		int outlived = 0;
		long end = r.death != null ? r.death.at : Long.MAX_VALUE;
		for (ItemRecord o : all) {
			if (o != r && o.bornAt > r.bornAt && o.death != null && o.death.at < end) outlived++;
		}
		if (outlived > 0) f.add("It outlived " + outlived + (outlived == 1 ? " younger item." : " younger items."));
		long realMs = (r.death != null ? r.death.at : System.currentTimeMillis()) - r.bornAt;
		f.add("It has been with you for " + Names.duration(realMs / 1000.0) + " of real time.");
		return f;
	}

	private static String fmt(double v) {
		if (v >= 100) return Names.num(v);
		if (v >= 10) return String.format(Locale.US, "%.0f", v);
		return String.format(Locale.US, "%.1f", v);
	}

	// ---------------------------------------------------------------- rankings

	public static List<String> ranks(ItemRecord r, Collection<ItemRecord> all) {
		List<String> out = new ArrayList<>();
		rank(out, r, all, "most blocks mined", ItemRecord::totalMined);
		rank(out, r, all, "most kills", ItemRecord::totalKills);
		rank(out, r, all, "most damage absorbed", x -> x.stat("absorbed"));
		rank(out, r, all, "farthest travelled", x -> x.stat("distance"));
		rank(out, r, all, "most diamonds", x -> x.stat("diamonds"));
		rank(out, r, all, "biggest hit", x -> x.stat("biggestHit"));
		rank(out, r, all, "oldest", x -> -x.bornAt);
		return out;
	}

	private static void rank(List<String> out, ItemRecord r, Collection<ItemRecord> all, String what, ToDoubleFunction<ItemRecord> m) {
		double v = m.applyAsDouble(r);
		if (v <= 0 && !"oldest".equals(what)) return;
		int better = 0;
		for (ItemRecord o : all) if (o != r && m.applyAsDouble(o) > v) better++;
		int place = better + 1;
		if (place <= 3 && all.size() > 2) {
			String medal = place == 1 ? "★ #1" : place == 2 ? "☆ #2" : "☆ #3";
			out.add(medal + " " + what + " of all " + all.size() + " items");
		}
	}

	/** Small helper so traits don't depend on Ids (main source set). */
	static final class ItemKinds {
		static boolean isArmor(String kind) {
			return "helmet".equals(kind) || "chestplate".equals(kind) || "leggings".equals(kind) || "boots".equals(kind) || "shield".equals(kind);
		}
	}
}
