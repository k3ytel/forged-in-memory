package dev.k3ytel.forgedinmemory.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The life story of one item. Plain fields so Gson can save it as JSON
 * (server: world folder; client: cache for the tree screen and tooltips).
 */
public class ItemRecord {
	public static final String ALIVE = "alive";
	public static final String MISSING = "missing";
	public static final String DEAD = "dead";

	public String id;
	/** Registry id, e.g. minecraft:diamond_pickaxe. Changes on smithing upgrades. */
	public String item;
	/** pickaxe, sword, helmet, bow... (see Ids.kind) */
	public String kind;
	/** Current display name (custom name or the item's normal name). */
	public String name;
	/** Every custom name it had, oldest first. */
	public List<String> names = new ArrayList<>();
	public String customName;

	public String owner;
	public String ownerName;
	public List<String> owners = new ArrayList<>();

	public String status = ALIVE;
	/** Tracked from the client on a vanilla server: guesses, not exact. */
	public boolean bestEffort;

	// Birth
	public long bornAt;
	public long bornDay;
	public String bornWorld;
	public String bornDim;
	public int[] bornPos;
	public String bornBiome;
	/** Birth key: crafted, smithed, picked_up, container:<what>, traded, fished, mob_drop:<entity>, already_owned, twin, other_world */
	public String how;
	public String maker;

	public List<Link> parents = new ArrayList<>();

	// Stats
	public Map<String, Long> mined = new HashMap<>();
	public Map<String, Long> kills = new HashMap<>();
	public Map<String, Long> caught = new HashMap<>();
	/** Generic counters, see Stat. */
	public Map<String, Double> stats = new HashMap<>();
	/** in-game day -> blocks mined that day */
	public Map<Long, Long> minedPerDay = new TreeMap<>();
	public Map<String, Integer> enchants = new LinkedHashMap<>();
	public List<String> dims = new ArrayList<>();
	public String lastKill;

	// Extra detail (v0.1 stats pack)
	/** biome id -> blocks mined there */
	public Map<String, Long> minedBiome = new HashMap<>();
	/** dimension id -> blocks mined there */
	public Map<String, Long> minedDim = new HashMap<>();
	/** damage kind (fall, explosion, fire, projectile, melee...) -> damage absorbed while worn */
	public Map<String, Double> absorbedBy = new HashMap<>();
	/** walking, sprinting, sneaking, swimming, riding, flying -> blocks travelled while held/worn */
	public Map<String, Double> distanceBy = new HashMap<>();
	/** dimension id -> seconds held/worn there */
	public Map<String, Double> timeIn = new HashMap<>();
	/** night, midair, onfire, underwater, clutch, revenge, baby, onehit, hostile, animal, player -> kills */
	public Map<String, Long> killKinds = new HashMap<>();
	/** biomes it has been in (held/worn) */
	public List<String> biomes = new ArrayList<>();
	/** custom-named mobs it killed */
	public List<String> namedVictims = new ArrayList<>();
	public String firstBlock;
	public String lastBlock;
	public String firstKill;
	public long firstKillDay = -1;

	// Story pack
	/** Birth conditions: time of day (ticks 0-24000), weather (clear/rain/thunder), moon phase 0-7 (0 = full) */
	public long bornTimeOfDay = -1;
	public String bornWeather;
	public int bornMoon = -1;
	/** mob id or "dmg:fall" etc -> damage taken from it while worn/held (find the nemesis) */
	public Map<String, Double> hitBy = new HashMap<>();
	/** in-game day -> kills that day */
	public Map<Long, Long> killsPerDay = new TreeMap<>();
	/** the last few things it did, oldest first: "mined|minecraft:stone|23", "killed|minecraft:zombie|1", "hit|minecraft:skeleton|2" */
	public List<String> lastMoments = new ArrayList<>();

	public void moment(String type, String target) {
		String key = type + "|" + target + "|";
		if (!lastMoments.isEmpty()) {
			String last = lastMoments.get(lastMoments.size() - 1);
			if (last.startsWith(key)) {
				long n = 1;
				try {
					n = Long.parseLong(last.substring(key.length()));
				} catch (NumberFormatException ignored) {
				}
				lastMoments.set(lastMoments.size() - 1, key + (n + 1));
				return;
			}
		}
		lastMoments.add(key + "1");
		while (lastMoments.size() > 8) lastMoments.remove(0);
	}

	public List<LifeEvent> events = new ArrayList<>();
	public Death death;

	// Where it is
	public long lastSeen;
	public String lastSeenDim;
	public int[] lastSeenPos;
	public long missingSince;
	public int lastDamage;
	public int maxDamage;

	public double stat(String key) {
		Double v = stats.get(key);
		return v == null ? 0 : v;
	}

	public void add(String key, double amount) {
		stats.merge(key, amount, Double::sum);
	}

	public void max(String key, double value) {
		Double v = stats.get(key);
		if (v == null || value > v) stats.put(key, value);
	}

	public void min(String key, double value) {
		Double v = stats.get(key);
		if (v == null || value < v) stats.put(key, value);
	}

	public long totalMined() {
		long t = 0;
		for (long v : mined.values()) t += v;
		return t;
	}

	public long totalKills() {
		long t = 0;
		for (long v : kills.values()) t += v;
		return t;
	}

	public void event(long day, String type, String a, String b) {
		events.add(new LifeEvent(System.currentTimeMillis(), day, type, a, b));
		if (events.size() > 400) events.remove(1); // keep the birth event
	}

	public boolean isDead() {
		return DEAD.equals(status);
	}

	public boolean isMissing() {
		return MISSING.equals(status);
	}

	public static class Link {
		/** material, successor, anvil, merge, twin */
		public String type;
		public String from;
		/** material units (ingots, planks...) for material links */
		public double amount;
		/** material family (iron, diamond, wood...) */
		public String what;

		public Link() {
		}

		public Link(String type, String from, double amount, String what) {
			this.type = type;
			this.from = from;
			this.amount = amount;
			this.what = what;
		}
	}

	public static class LifeEvent {
		public long at;
		public long day;
		/** born, renamed, enchanted, disenchanted, upgraded, owner, recovered, revived, dropped, missing, died, repaired, sacrificed_into, absorbed, twin, death_witness */
		public String type;
		public String a;
		public String b;

		public LifeEvent() {
		}

		public LifeEvent(long at, long day, String type, String a, String b) {
			this.at = at;
			this.day = day;
			this.type = type;
			this.a = a;
			this.b = b;
		}
	}

	public static class Death {
		/** broke, lava, fire, cactus, explosion, lightning, void, despawned, lost, sacrificed, merged, destroyed */
		public String cause;
		/** what it was doing: mining, fighting, hit_by, flying, using, none */
		public String action;
		/** block / entity / item id that goes with action or cause */
		public String target;
		/** for sacrificed/merged: the item id it went into */
		public String into;
		public boolean whileMissing;
		public long at;
		public long day;
		public String dim;
		public int[] pos;
		public String ownerName;
		/** biome at the place it died */
		public String biome;
		/** other players within 48 blocks */
		public List<String> witnesses = new ArrayList<>();
	}
}
