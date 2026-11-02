package dev.k3ytel.forgedinmemory.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import dev.k3ytel.forgedinmemory.ForgedInMemory;
import dev.k3ytel.forgedinmemory.core.Ids;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import dev.k3ytel.forgedinmemory.core.ItemRecord.Link;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Test commands, registered ONLY in the development environment (gradlew runClient), never in a real install.
 * /fim demo   – a whole fake history of ~25 items with family links, deaths of every cause, records
 * /fim boost  – fills the held item with stats (crosses milestones → toasts)
 * /fim kill   – the held item dies (random cause) → toast, bell, ghost, obituary
 * /fim clear  – forget all of your records
 */
public final class DevCommands {
	private DevCommands() {
	}

	public static void register() {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> dispatcher.register(
				Commands.literal("fim")
						.then(Commands.literal("demo").executes(c -> run(c.getSource(), DevCommands::demo)))
						.then(Commands.literal("boost").executes(c -> run(c.getSource(), DevCommands::boost)))
						.then(Commands.literal("kill").executes(c -> run(c.getSource(), DevCommands::kill)))
						.then(Commands.literal("clear").executes(c -> run(c.getSource(), DevCommands::clear)))));
		ForgedInMemory.LOGGER.info("[Forged in Memory] Dev commands registered (/fim demo|boost|kill|clear)");
	}

	private interface Action {
		String run(ServerPlayer p);
	}

	private static int run(CommandSourceStack src, Action a) {
		ServerPlayer p = src.getPlayer();
		if (p == null || !Tracker.active()) {
			src.sendFailure(Component.literal("Run this as a player in a world."));
			return 0;
		}
		String msg = a.run(p);
		src.sendSuccess(() -> Component.literal("[Forged in Memory] " + msg), false);
		return 1;
	}

	// ---------------------------------------------------------------- boost / kill / clear

	private static ItemRecord held(ServerPlayer p) {
		ItemStack s = p.getMainHandItem();
		if (!Ids.trackable(s)) return null;
		return Tracker.archive().records.get(Ids.get(s));
	}

	private static String boost(ServerPlayer p) {
		ItemRecord r = held(p);
		if (r == null) return "Hold a tracked item (wait a second after picking it up).";
		Random rnd = new Random();
		long day = r.bornDay;
		add(r.mined, "minecraft:stone", 700 + rnd.nextInt(400));
		add(r.mined, "minecraft:deepslate", 300 + rnd.nextInt(300));
		add(r.mined, "minecraft:diamond_ore", 6);
		add(r.mined, "minecraft:deepslate_diamond_ore", 8);
		add(r.mined, "minecraft:iron_ore", 40);
		add(r.mined, "minecraft:ancient_debris", 4);
		add(r.mined, "minecraft:oak_log", 64);
		for (int d = 0; d < 8; d++) r.minedPerDay.merge(day + d, 60L + rnd.nextInt(200), Long::sum);
		r.add("diamonds", 14);
		r.add("debris", 4);
		r.add("ores", 54);
		r.add("logs", 64);
		r.add("darkMined", 120);
		r.min("deepestY", -59);
		r.max("highestY", 140);
		r.max("longestDig", 312);
		r.max("bestMinute", 74);
		add(r.kills, "minecraft:zombie", 60);
		add(r.kills, "minecraft:skeleton", 35);
		add(r.kills, "minecraft:creeper", 12);
		add(r.kills, "minecraft:enderman", 6);
		add(r.killKinds, "hostile", 113);
		add(r.killKinds, "midair", 30);
		add(r.killKinds, "night", 70);
		add(r.killKinds, "clutch", 4);
		add(r.killKinds, "onehit", 22);
		r.add("damageDealt", 1900);
		r.add("hitsLanded", 260);
		r.max("biggestHit", 19);
		r.max("bestStreak", 7);
		r.add("crits", 48);
		r.add("distance", 12000);
		r.distanceBy.merge("sprinting", 7000.0, Double::sum);
		r.distanceBy.merge("walking", 5000.0, Double::sum);
		r.add("heldSec", 9000);
		r.add("jumps", 2400);
		r.add("xp", 3100);
		r.add("nightSec", 2500);
		r.add("durabilityUsed", 900);
		if (r.firstKill == null) {
			r.firstKill = "minecraft:zombie";
			r.firstKillDay = day;
		}
		r.lastKill = "minecraft:creeper";
		Tracker.markChanged(r);
		return "Boosted " + r.name + ". Milestone toasts should pop in a second.";
	}

	private static String kill(ServerPlayer p) {
		ItemRecord r = held(p);
		if (r == null) return "Hold a tracked item.";
		String[] causes = {"broke", "lava", "fire", "cactus", "explosion", "lightning", "void", "despawned", "lost"};
		String cause = causes[new Random().nextInt(causes.length)];
		p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
		Tracker.killForTest(r, cause, p);
		return r.name + " died: " + cause;
	}

	private static String clear(ServerPlayer p) {
		String owner = p.getUUID().toString();
		int n = 0;
		var it = Tracker.archive().records.values().iterator();
		while (it.hasNext()) {
			if (owner.equals(it.next().owner)) {
				it.remove();
				n++;
			}
		}
		Tracker.archive().heirs.remove(owner);
		Tracker.archive().ledger.remove(owner);
		Tracker.sendFull(p);
		return "Forgot " + n + " items. (Items in your inventory get new records in a moment.)";
	}

	private static <K> void add(Map<K, Long> m, K k, long v) {
		m.merge(k, v, Long::sum);
	}

	// ---------------------------------------------------------------- demo world history

	private static final class Demo {
		final ServerPlayer p;
		final long today;
		final long now = System.currentTimeMillis();
		final Random rnd = new Random();
		final List<ItemRecord> made = new ArrayList<>();

		Demo(ServerPlayer p, long today) {
			this.p = p;
			this.today = today;
		}

		/** A record born `daysAgo` in-game days ago. */
		ItemRecord item(String item, String kind, String name, long daysAgo, String how) {
			ItemRecord r = new ItemRecord();
			r.id = Ids.newId();
			r.item = item;
			r.kind = kind;
			r.name = name;
			r.owner = p.getUUID().toString();
			r.ownerName = p.getName().getString();
			r.owners.add(r.owner);
			r.bornDay = Math.max(0, today - daysAgo);
			r.bornAt = now - daysAgo * 20 * 60_000L;
			r.bornWorld = "Demo World";
			r.bornDim = "minecraft:overworld";
			r.bornPos = new int[] {rnd.nextInt(400) - 200, 64 + rnd.nextInt(10), rnd.nextInt(400) - 200};
			r.bornBiome = "minecraft:plains";
			r.how = how;
			r.maker = "crafted".equals(how) ? r.ownerName : null;
			r.dims.add("minecraft:overworld");
			r.biomes.add("minecraft:plains");
			r.biomes.add("minecraft:forest");
			r.event(r.bornDay, "born", how, r.maker);
			r.bornTimeOfDay = rnd.nextInt(24000);
			r.bornMoon = rnd.nextInt(8);
			int w = rnd.nextInt(10);
			r.bornWeather = w == 0 ? "thunder" : w < 3 ? "rain" : "clear";
			if (rnd.nextInt(4) == 0) r.add("drops", 1 + rnd.nextInt(7));
			made.add(r);
			return r;
		}

		void mat(ItemRecord child, ItemRecord parent, double amount, String what) {
			child.parents.add(new Link("material", parent.id, amount, what));
		}

		void link(ItemRecord child, ItemRecord parent, String type) {
			child.parents.add(new Link(type, parent.id, 0, null));
		}

		void mine(ItemRecord r, long total, String... blocks) {
			long left = total;
			for (int i = 0; i < blocks.length; i++) {
				long v = i == blocks.length - 1 ? left : (long) (left * (0.45 + rnd.nextDouble() * 0.2));
				r.mined.merge(blocks[i], v, Long::sum);
				left -= v;
			}
			long days = Math.max(1, today - r.bornDay);
			for (long d = 0; d < Math.min(days, 20); d++) r.minedPerDay.merge(r.bornDay + d, total / Math.min(days, 20) + rnd.nextInt(20), Long::sum);
			r.add("ores", total / 30);
			r.add("darkMined", total / 8);
			r.min("deepestY", -40 - rnd.nextInt(20));
			r.max("highestY", 70 + rnd.nextInt(60));
			r.max("longestDig", Math.min(total, 40 + rnd.nextInt(400)));
			r.max("bestMinute", Math.min(total, 20 + rnd.nextInt(60)));
			r.firstBlock = blocks[blocks.length - 1];
			r.lastBlock = blocks[0];
			r.moment("mined", blocks[1 % blocks.length]);
			r.moment("mined", blocks[0]);
			r.moment("mined", blocks[0]);
			r.minedBiome.put("minecraft:plains", total / 3);
			r.minedBiome.put("minecraft:dripstone_caves", total / 3);
			r.minedBiome.put("minecraft:deep_dark", total - 2 * (total / 3));
			r.minedDim.put("minecraft:overworld", total);
			life(r, total * 2);
		}

		void fight(ItemRecord r, long zombies, long skeletons, long creepers, long others) {
			r.kills.merge("minecraft:zombie", zombies, Long::sum);
			r.kills.merge("minecraft:skeleton", skeletons, Long::sum);
			r.kills.merge("minecraft:creeper", creepers, Long::sum);
			if (others > 0) {
				r.kills.merge("minecraft:spider", others / 2, Long::sum);
				r.kills.merge("minecraft:cow", others - others / 2, Long::sum);
			}
			long total = r.totalKills();
			r.killKinds.put("hostile", total - (others - others / 2));
			r.killKinds.put("animal", others - others / 2);
			r.killKinds.put("night", total * 2 / 3);
			r.killKinds.put("midair", total / 5);
			r.killKinds.put("onehit", total / 4);
			r.killKinds.put("clutch", Math.max(1, total / 40));
			r.killKinds.put("revenge", total / 10);
			if (others > 4) r.killKinds.put("baby", 3L);
			r.add("hitsLanded", total * 3);
			r.add("damageDealt", total * 18);
			r.max("biggestHit", 12 + rnd.nextInt(10));
			r.add("crits", total / 3);
			r.max("bestStreak", 3 + rnd.nextInt(9));
			r.max("longestKill", 3 + rnd.nextInt(4));
			r.firstKill = "minecraft:zombie";
			r.firstKillDay = r.bornDay;
			r.lastKill = "minecraft:skeleton";
			for (long d = 0; d < 10; d++) r.killsPerDay.merge(r.bornDay + d, total / 10 + rnd.nextInt(8), Long::sum);
			r.moment("killed", "minecraft:zombie");
			r.moment("killed", "minecraft:zombie");
			r.moment("hit", "minecraft:skeleton");
			r.moment("killed", "minecraft:skeleton");
			r.hitBy.put("minecraft:skeleton", 60.0 + rnd.nextInt(200));
			r.hitBy.put("minecraft:zombie", 40.0 + rnd.nextInt(100));
			life(r, total * 60);
		}

		void armor(ItemRecord r, double absorbed, long hits) {
			r.add("hitsTaken", hits);
			r.add("absorbed", absorbed);
			r.absorbedBy.put("melee", absorbed * 0.5);
			r.absorbedBy.put("projectile", absorbed * 0.25);
			r.absorbedBy.put("explosion", absorbed * 0.15);
			r.absorbedBy.put("fall", absorbed * 0.1);
			r.max("worstHit", 14 + rnd.nextInt(8));
			r.add("fallsTaken", hits / 12);
			r.add("explosionsSurvived", hits / 30);
			r.add("wornSec", hits * 50);
			r.min("closestCall", 1 + rnd.nextInt(3));
			r.add("deathsWorn", rnd.nextInt(3));
			r.hitBy.put("minecraft:skeleton", absorbed * 0.3);
			r.hitBy.put("minecraft:creeper", absorbed * 0.2);
			r.hitBy.put("dmg:fall", absorbed * 0.1);
			r.add("savedLife", rnd.nextInt(4));
			r.max("biggestFall", 12 + rnd.nextInt(30));
			r.moment("hit", "minecraft:skeleton");
			r.moment("hit", "dmg:fall");
			world(r, hits * 50);
		}

		void life(ItemRecord r, double seconds) {
			r.add("heldSec", seconds);
			r.add("durabilityUsed", seconds / 4);
			world(r, seconds);
		}

		void world(ItemRecord r, double seconds) {
			r.add("distance", seconds * 2.5);
			r.distanceBy.put("walking", seconds * 1.2);
			r.distanceBy.put("sprinting", seconds * 1.0);
			r.distanceBy.put("swimming", seconds * 0.1);
			r.distanceBy.put("riding", seconds * 0.2);
			r.timeIn.put("minecraft:overworld", seconds * 0.85);
			if (seconds > 2000) {
				r.timeIn.put("minecraft:the_nether", seconds * 0.15);
				if (!r.dims.contains("minecraft:the_nether")) r.dims.add("minecraft:the_nether");
				r.biomes.add("minecraft:nether_wastes");
			}
			r.add("nightSec", seconds * 0.4);
			r.add("darkSec", seconds * 0.2);
			r.add("underwaterSec", seconds * 0.02);
			r.add("rainSec", seconds * 0.05);
			r.add("jumps", seconds / 4);
			r.add("xp", seconds / 3);
			r.add("trades", seconds / 600);
			r.add("sleeps", seconds / 1200);
			r.max("highestVisited", 120 + rnd.nextInt(80));
			r.add("sunsets", seconds / 1200);
			r.add("fullMoons", seconds / 9600);
			r.max("farthestFromHome", Math.min(9000, seconds / 3));
			r.min("lowestVisited", -50 - rnd.nextInt(14));
			r.biomes.add("minecraft:desert");
			r.biomes.add("minecraft:taiga");
		}

		void die(ItemRecord r, long daysAgo, String cause, String action, String target) {
			ItemRecord.Death d = new ItemRecord.Death();
			d.cause = cause;
			d.action = action;
			d.target = target;
			d.day = Math.max(r.bornDay, today - daysAgo);
			d.at = now - daysAgo * 20 * 60_000L;
			d.dim = "minecraft:overworld";
			d.pos = new int[] {rnd.nextInt(400) - 200, 10 + rnd.nextInt(60), rnd.nextInt(400) - 200};
			d.ownerName = r.ownerName;
			d.whileMissing = "lost".equals(cause);
			String[] biomes = {"minecraft:deep_dark", "minecraft:dripstone_caves", "minecraft:plains", "minecraft:badlands", "minecraft:dark_forest", "minecraft:desert"};
			d.biome = biomes[rnd.nextInt(biomes.length)];
			if (rnd.nextInt(3) == 0) d.witnesses.add("Alex");
			r.status = ItemRecord.DEAD;
			r.death = d;
			r.event(d.day, "died", cause, target);
		}

		void enchant(ItemRecord r, String... ench) {
			for (int i = 0; i + 1 < ench.length; i += 2) {
				r.enchants.put("minecraft:" + ench[i], Integer.parseInt(ench[i + 1]));
				r.event(r.bornDay + 1, "enchanted", "minecraft:" + ench[i], ench[i + 1]);
			}
			r.add("levelsSpent", 30 + rnd.nextInt(40));
			r.add("tableEnchants", 1);
			r.add("anvilUses", 2);
		}
	}

	private static String demo(ServerPlayer p) {
		long today = Math.max(60, p.level().getOverworldClockTime() / 24000L);
		Demo d = new Demo(p, today);

		// ---- Pickaxes: a family line from wood to netherite
		ItemRecord woodPick = d.item("minecraft:wooden_pickaxe", "pickaxe", "Wooden Pickaxe", 60, "crafted");
		d.mine(woodPick, 59, "minecraft:stone", "minecraft:dirt");
		d.die(woodPick, 59, "broke", "mining", "minecraft:stone");
		ItemRecord woodAxe = d.item("minecraft:wooden_axe", "axe", "Wooden Axe", 60, "crafted");
		d.mine(woodAxe, 59, "minecraft:oak_log", "minecraft:birch_log");
		woodAxe.add("logs", 59);
		d.die(woodAxe, 58, "broke", "mining", "minecraft:oak_log");

		ItemRecord stonePick = d.item("minecraft:stone_pickaxe", "pickaxe", "Stone Pickaxe", 59, "crafted");
		d.mat(stonePick, woodPick, 3, "stone");
		d.mat(stonePick, woodAxe, 1, "wood");
		d.link(stonePick, woodPick, "successor");
		d.mine(stonePick, 131, "minecraft:stone", "minecraft:iron_ore", "minecraft:coal_ore");
		d.die(stonePick, 56, "broke", "mining", "minecraft:iron_ore");

		ItemRecord stoneAxe = d.item("minecraft:stone_axe", "axe", "Stone Axe", 58, "crafted");
		d.mat(stoneAxe, woodPick, 3, "stone");
		d.mat(stoneAxe, woodAxe, 1, "wood");
		d.link(stoneAxe, woodAxe, "successor");
		d.mine(stoneAxe, 131, "minecraft:spruce_log", "minecraft:oak_log");
		stoneAxe.add("logs", 131);
		d.die(stoneAxe, 40, "broke", "mining", "minecraft:spruce_log");

		ItemRecord ironPick = d.item("minecraft:iron_pickaxe", "pickaxe", "Iron Pickaxe", 55, "crafted");
		d.mat(ironPick, stonePick, 3, "iron");
		d.mat(ironPick, stoneAxe, 1, "wood");
		d.link(ironPick, stonePick, "successor");
		d.mine(ironPick, 2400, "minecraft:deepslate", "minecraft:stone", "minecraft:diamond_ore");
		ironPick.add("diamonds", 9);
		d.die(ironPick, 45, "lava", null, null);

		ItemRecord diaPick = d.item("minecraft:netherite_pickaxe", "pickaxe", "Old Faithful", 44, "crafted");
		diaPick.customName = "Old Faithful";
		diaPick.names.add("Old Faithful");
		d.mat(diaPick, ironPick, 3, "diamond");
		d.mat(diaPick, stoneAxe, 1, "wood");
		d.link(diaPick, ironPick, "successor");
		d.mine(diaPick, 21500, "minecraft:deepslate", "minecraft:stone", "minecraft:tuff", "minecraft:deepslate_diamond_ore");
		diaPick.add("diamonds", 71);
		diaPick.add("debris", 22);
		diaPick.add("mendingRepaired", 9000);
		diaPick.add("repairsAnvil", 2);
		diaPick.maxDamage = 2031;
		diaPick.lastDamage = 240;
		d.enchant(diaPick, "efficiency", "5", "unbreaking", "3", "fortune", "3", "mending", "1");
		diaPick.event(today - 30, "upgraded", "minecraft:diamond_pickaxe", "minecraft:netherite_pickaxe");
		diaPick.event(today - 20, "dimension", "minecraft:the_nether", null);

		ItemRecord ironAxe = d.item("minecraft:iron_axe", "axe", "Iron Axe", 40, "crafted");
		d.mat(ironAxe, stonePick, 3, "iron");
		d.link(ironAxe, stoneAxe, "successor");
		d.mine(ironAxe, 1800, "minecraft:oak_log", "minecraft:dark_oak_log", "minecraft:jungle_log");
		ironAxe.add("logs", 1800);
		ironAxe.maxDamage = 250;
		ironAxe.lastDamage = 120;

		ItemRecord shovel = d.item("minecraft:iron_shovel", "shovel", "Iron Shovel", 30, "crafted");
		d.mat(shovel, ironPick, 1, "iron");
		d.mine(shovel, 3200, "minecraft:dirt", "minecraft:sand", "minecraft:gravel");
		d.die(shovel, 4, "despawned", null, null);

		ItemRecord hoe = d.item("minecraft:diamond_hoe", "hoe", "Diamond Hoe", 25, "crafted");
		d.mat(hoe, diaPick, 2, "diamond");
		hoe.add("tilled", 640);
		hoe.add("crops", 1450);
		d.mine(hoe, 1500, "minecraft:wheat", "minecraft:carrots");

		// ---- Blades
		ItemRecord woodSword = d.item("minecraft:wooden_sword", "sword", "Wooden Sword", 59, "crafted");
		d.fight(woodSword, 9, 3, 1, 2);
		d.die(woodSword, 57, "broke", "fighting", "minecraft:zombie");
		ItemRecord stoneSword = d.item("minecraft:stone_sword", "sword", "Stone Sword", 57, "crafted");
		d.mat(stoneSword, woodPick, 2, "stone");
		d.link(stoneSword, woodSword, "successor");
		d.fight(stoneSword, 30, 15, 6, 10);
		ItemRecord ironSword = d.item("minecraft:iron_sword", "sword", "Iron Sword", 50, "crafted");
		d.mat(ironSword, stonePick, 2, "iron");
		d.link(ironSword, stoneSword, "anvil");
		d.die(stoneSword, 49, "sacrificed", null, null);
		stoneSword.death.into = ironSword.id;
		d.fight(ironSword, 120, 60, 25, 30);
		d.die(ironSword, 33, "lost", null, null);
		ItemRecord dragonbane = d.item("minecraft:netherite_sword", "sword", "Dragonbane", 32, "crafted");
		dragonbane.customName = "Dragonbane";
		dragonbane.names.add("Dragonbane");
		d.mat(dragonbane, ironPick, 2, "diamond");
		d.link(dragonbane, ironSword, "successor");
		d.fight(dragonbane, 410, 260, 90, 60);
		dragonbane.kills.put("minecraft:ender_dragon", 1L);
		dragonbane.kills.put("minecraft:wither", 1L);
		dragonbane.add("bosses", 2);
		dragonbane.event(today - 10, "boss", "minecraft:ender_dragon", null);
		dragonbane.namedVictims.add("Steve's Pet Zombie");
		d.enchant(dragonbane, "sharpness", "5", "looting", "3", "sweeping_edge", "3", "mending", "1");
		ItemRecord mace = d.item("minecraft:mace", "mace", "Mace", 6, "crafted");
		d.fight(mace, 40, 10, 5, 0);
		mace.max("biggestHit", 64);
		mace.killKinds.put("midair", 50L);

		// ---- Archery
		ItemRecord bow = d.item("minecraft:bow", "bow", "Bow", 48, "picked_up");
		bow.add("shots", 1300);
		bow.add("hits", 870);
		bow.max("longestShot", 74);
		d.fight(bow, 60, 220, 70, 20);
		bow.max("longestKill", 74);
		d.enchant(bow, "power", "5", "infinity", "1");
		ItemRecord crossbow = d.item("minecraft:crossbow", "crossbow", "Crossbow", 35, "traded");
		crossbow.add("shots", 140);
		crossbow.add("hits", 95);
		d.fight(crossbow, 10, 20, 5, 0);
		d.die(crossbow, 20, "explosion", null, "minecraft:creeper");
		ItemRecord trident = d.item("minecraft:trident", "trident", "Trident", 15, "mob_drop:minecraft:drowned");
		trident.add("throws", 210);
		trident.add("tridentHits", 150);
		d.fight(trident, 20, 0, 0, 30);

		// ---- Armor
		ItemRecord ironHelmet = d.item("minecraft:iron_helmet", "helmet", "Iron Helmet", 52, "crafted");
		d.mat(ironHelmet, stonePick, 5, "iron");
		d.armor(ironHelmet, 220, 160);
		d.die(ironHelmet, 30, "broke", "hit_by", "minecraft:skeleton");
		ItemRecord diaHelmet = d.item("minecraft:diamond_helmet", "helmet", "Diamond Helmet", 30, "crafted");
		d.mat(diaHelmet, ironPick, 5, "diamond");
		d.link(diaHelmet, ironHelmet, "successor");
		d.armor(diaHelmet, 900, 700);
		ItemRecord chest = d.item("minecraft:netherite_chestplate", "chestplate", "The Wall", 40, "crafted");
		chest.customName = "The Wall";
		chest.names.add("The Wall");
		d.mat(chest, diaPick, 8, "diamond");
		d.armor(chest, 4300, 2600);
		d.enchant(chest, "protection", "4", "unbreaking", "3", "mending", "1");
		ItemRecord legs = d.item("minecraft:diamond_leggings", "leggings", "Diamond Leggings", 38, "crafted");
		d.mat(legs, diaPick, 7, "diamond");
		d.armor(legs, 1500, 2100);
		ItemRecord boots = d.item("minecraft:leather_boots", "boots", "Leather Boots", 57, "crafted");
		d.armor(boots, 40, 60);
		d.die(boots, 54, "cactus", null, null);
		ItemRecord ironBoots = d.item("minecraft:iron_boots", "boots", "Iron Boots", 54, "crafted");
		d.mat(ironBoots, stonePick, 4, "iron");
		d.link(ironBoots, boots, "successor");
		d.armor(ironBoots, 300, 400);

		// ---- Shield, wings, odd tools
		ItemRecord shield = d.item("minecraft:shield", "shield", "Shield", 45, "crafted");
		d.mat(shield, stonePick, 1, "iron");
		d.mat(shield, ironAxe, 6, "wood");
		shield.add("blocked", 640);
		shield.add("projectilesBlocked", 420);
		shield.add("meleeBlocked", 200);
		shield.add("explosionsBlocked", 20);
		shield.add("blockedDamage", 3100);
		shield.max("biggestBlocked", 49);
		d.life(shield, 20000);
		ItemRecord elytra = d.item("minecraft:elytra", "elytra", "Elytra", 12, "picked_up");
		elytra.add("flightDist", 164000);
		elytra.add("flightSec", 7300);
		elytra.max("longestFlight", 640);
		elytra.max("topSpeed", 67);
		elytra.max("highestFlight", 311);
		elytra.add("rockets", 980);
		elytra.add("crashes", 3);
		elytra.add("wornSec", 9000);
		d.world(elytra, 9000);
		elytra.dims.add("minecraft:the_end");
		ItemRecord rod = d.item("minecraft:fishing_rod", "fishing_rod", "Fishing Rod", 42, "crafted");
		d.mat(rod, woodAxe, 1.5, "wood");
		rod.add("casts", 560);
		rod.add("fish", 230);
		rod.add("treasure", 18);
		rod.add("junk", 31);
		rod.caught.put("minecraft:cod", 150L);
		rod.caught.put("minecraft:salmon", 70L);
		rod.caught.put("minecraft:pufferfish", 10L);
		rod.caught.put("minecraft:enchanted_book", 11L);
		rod.caught.put("minecraft:name_tag", 7L);
		rod.caught.put("minecraft:bowl", 31L);
		ItemRecord shears = d.item("minecraft:shears", "shears", "Shears", 50, "crafted");
		d.mat(shears, stonePick, 2, "iron");
		shears.add("sheared", 180);
		d.die(shears, 22, "void", null, null);
		ItemRecord flint = d.item("minecraft:flint_and_steel", "flint_and_steel", "Flint and Steel", 36, "crafted");
		flint.add("fires", 64);
		d.die(flint, 35, "fire", null, null);

		for (ItemRecord r : d.made) {
			Tracker.archive().records.put(r.id, r);
		}
		Tracker.markAllDirty();
		Tracker.sendFull(p);
		return "Created " + d.made.size() + " demo items. Press Home to see the tree.";
	}
}
