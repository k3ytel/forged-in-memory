package dev.k3ytel.forgedinmemory.client;

import dev.k3ytel.forgedinmemory.core.ItemRecord;

/** Every item earns an epithet from what it did most: "the Deep Delver", "Dragonslayer"... */
public final class Titles {
	private Titles() {
	}

	public static String of(ItemRecord r) {
		long kills = r.totalKills();
		long mined = r.totalMined();
		long dragons = r.kills.getOrDefault("minecraft:ender_dragon", 0L);
		long withers = r.kills.getOrDefault("minecraft:wither", 0L);
		long wardens = r.kills.getOrDefault("minecraft:warden", 0L);
		if (dragons > 0) return "Dragonslayer";
		if (withers > 0) return "Witherbane";
		if (wardens > 0) return "Silencer of the Deep";
		if (r.killKinds.getOrDefault("player", 0L) >= 10) return "the Duelist";
		if (kills >= 1000) return "the Reaper";
		if (r.stat("diamonds") >= 50) return "the Diamond Hunter";
		if (r.stat("debris") >= 16) return "the Netherite Seeker";
		if (mined >= 50000) return "the Mountain-Mover";
		if (r.stat("absorbed") >= 2000) return "the Unbreakable Wall";
		if (r.stat("flightDist") >= 100000) return "the Sky Wanderer";
		if (r.stat("blocked") >= 500) return "the Bulwark";
		if (kills >= 200) return "the Butcher";
		if (r.killKinds.getOrDefault("baby", 0L) >= 10) return "the Monster";
		if (r.stat("crops") >= 1000) return "the Harvester";
		if (r.stat("fish") >= 200) return "the Angler";
		if (mined >= 10000) return "the Deep Delver";
		if (r.stat("deathsWorn") + r.stat("deathsHeld") >= 5 && !r.isDead()) return "the Survivor";
		if (r.maxDamage > 0 && r.stat("mendingRepaired") >= r.maxDamage * 3L) return "the Phoenix";
		if (r.stat("diamonds") >= 10) return "the Diamond Finder";
		if (r.killKinds.getOrDefault("clutch", 0L) >= 10) return "the Clutch";
		if (r.killKinds.getOrDefault("midair", 0L) >= 25) return "the Acrobat";
		if (r.stat("longestKill") >= 60) return "the Sharpshooter";
		if (kills >= 50) return "the Fighter";
		if (mined >= 1000) return "the Miner";
		if (r.stat("lavaSec") >= 60) return "the Fireproof";
		if (r.stat("underwaterSec") >= 1800) return "the Diver";
		if (r.biomes.size() >= 25) return "the Explorer";
		if (r.stat("trades") >= 100) return "the Merchant";
		if ("minecraft:the_nether".equals(r.bornDim)) return "the Nether-Born";
		if ("minecraft:the_end".equals(r.bornDim)) return "the Void-Born";
		long days = Math.max(0, (r.death != null ? r.death.day : Names.today()) - r.bornDay);
		if (days >= 100) return "the Ancient";
		if (days >= 30) return "the Veteran";
		if (r.isDead() && days == 0 && (r.death.at - r.bornAt) < 10 * 60_000L) return "Gone Too Soon";
		if (mined + kills < 10) return "the Newborn";
		return "the Faithful";
	}

	public static String full(ItemRecord r) {
		String t = of(r);
		return t.startsWith("the ") || t.equals("Gone Too Soon") ? r.name + ", " + t : r.name + " " + t;
	}
}
