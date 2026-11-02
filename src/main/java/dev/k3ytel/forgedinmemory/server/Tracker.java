package dev.k3ytel.forgedinmemory.server;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.k3ytel.forgedinmemory.ForgedInMemory;
import dev.k3ytel.forgedinmemory.core.Archive;
import dev.k3ytel.forgedinmemory.core.Ids;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import dev.k3ytel.forgedinmemory.core.ItemRecord.Link;
import dev.k3ytel.forgedinmemory.net.Net;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * Server side of Forged in Memory (runs in singleplayer too). Gives items their hidden ID,
 * records everything they do, notices when they die and keeps the archive saved in the world folder.
 */
public final class Tracker {
	private static final Gson GSON = new GsonBuilder().create();
	/** Ticks a dropped-on-death item can stay missing before it counts as dead (5 min). */
	public static final long MISSING_TICKS = 6000;

	private static MinecraftServer server;
	private static Archive archive;
	private static Path file;
	private static boolean dirty;
	private static final Set<String> dirtyIds = new HashSet<>();

	private record Pending(String item, String how, List<Link> parents, String maker, long tick) {
	}

	private record Action(String type, String target, long tick) {
	}

	private record Attr(String dim, Vec3 pos, String tool, long tick, String origin) {
	}

	private static final Map<UUID, Deque<Pending>> pending = new HashMap<>();
	private static final Map<UUID, Action> lastAction = new HashMap<>();
	private static final Map<UUID, String> breakingTool = new HashMap<>();
	private static final Map<UUID, Long> dyingTick = new HashMap<>();
	private static final Map<UUID, Vec3> lastPos = new HashMap<>();
	private static final Map<UUID, String> lastDim = new HashMap<>();
	private static final Map<String, long[]> streaks = new HashMap<>();
	private static final Map<String, Long> anvilTouched = new HashMap<>();
	private static final List<Attr> attributions = new ArrayList<>();
	private static final Map<ItemEntity, String> entityTool = new WeakHashMap<>();
	private static final Map<ItemEntity, String> entityOrigin = new WeakHashMap<>();
	private static final Map<ItemEntity, DamageSource> itemDamage = new WeakHashMap<>();
	private static final Map<FishingHook, String> hookRod = new WeakHashMap<>();
	/** tool id -> {blocks in current dig, last break tick} */
	private static final Map<String, long[]> digSessions = new HashMap<>();
	/** tool id -> break ticks in the last minute */
	private static final Map<String, ArrayDeque<Long>> digWindow = new HashMap<>();
	/** player -> last vanilla stat values (jumps, trades...) */
	private static final Map<UUID, int[]> lastStats = new HashMap<>();
	/** player -> seconds of the current elytra flight */
	private static final Map<UUID, Integer> flightRun = new HashMap<>();

	/** Vanilla per-player counters that get credited to every item held or worn when they go up. */
	private static final String[] STAT_KEYS = {"jumps", "bred", "trades", "sleeps", "raids", "cake", "records", "bells", "targets", "xp", "rockets"};

	/** What happened to the player in the last second, shared by all items held/worn. */
	private record Second(double moved, String mode, int[] statDelta, boolean flying, boolean sunset, boolean fullMoon) {
	}

	/** player -> was it night last second (for counting sunsets) */
	private static final Map<UUID, Boolean> wasDark = new HashMap<>();

	private static final long[] MINED_MILESTONES = {100, 1000, 5000, 10000, 25000, 50000, 100000};
	private static final long[] KILL_MILESTONES = {10, 100, 500, 1000, 5000};

	private Tracker() {
	}

	// ------------------------------------------------------------------ setup

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(Tracker::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
			save();
			archive = null;
			server = null;
			clearTransient();
		});
		ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> {
			if (dirty) save();
		});
		ServerTickEvents.END_SERVER_TICK.register(Tracker::tick);

		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
			beforeBreak(player, pos, state);
			return true;
		});
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> afterBreak(level, player, pos, state));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!level.isClientSide()) setAction(player, "fighting", entityKey(entity));
			return InteractionResult.PASS;
		});
		ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
			if (entity instanceof ServerPlayer p) {
				Entity src = source.getEntity();
				if (src != null) setAction(p, "hit_by", entityKey(src));
				else if (p.isFallFlying()) setAction(p, "flying", "");
			}
			return true;
		});
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (archive == null) return true;
			if (entity instanceof ServerPlayer p) dyingTick.put(p.getUUID(), (long) server.getTickCount());
			// Loot drops inside die(), before AFTER_DEATH fires: link the drops to the weapon now.
			if (source.getEntity() instanceof ServerPlayer killer && !ignored(killer) && entity != killer) {
				ItemRecord w = rec(source.getWeaponItem());
				attributions.add(new Attr(dimKey(entity.level()), entity.position(), w == null ? null : w.id, server.getTickCount(), "mob_drop:" + entityKey(entity)));
			}
			return true;
		});
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Tracker::afterDamage);
		ServerLivingEntityEvents.AFTER_DEATH.register(Tracker::afterDeath);
		ServerEntityEvents.ENTITY_LOAD.register(Tracker::onLoad);
		ServerEntityEvents.ENTITY_UNLOAD.register(Tracker::onUnload);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) -> {
			ServerPlayer p = handler.getPlayer();
			srv.execute(() -> fullSync(p));
		});
		ServerPlayNetworking.registerGlobalReceiver(Net.RequestPayload.TYPE, (payload, ctx) -> fullSync(ctx.player()));
	}

	private static void clearTransient() {
		dirtyIds.clear();
		pending.clear();
		lastAction.clear();
		breakingTool.clear();
		dyingTick.clear();
		lastPos.clear();
		lastDim.clear();
		streaks.clear();
		anvilTouched.clear();
		attributions.clear();
		entityTool.clear();
		entityOrigin.clear();
		itemDamage.clear();
		hookRod.clear();
		digSessions.clear();
		digWindow.clear();
		lastStats.clear();
		flightRun.clear();
		wasDark.clear();
	}

	private static void load(MinecraftServer s) {
		server = s;
		clearTransient();
		file = s.getWorldPath(LevelResource.ROOT).resolve("forged-in-memory").resolve("archive.json");
		archive = null;
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				archive = GSON.fromJson(r, Archive.class);
			} catch (Exception e) {
				ForgedInMemory.LOGGER.error("[Forged in Memory] Could not read {}, starting a new archive (old file kept as .broken)", file, e);
				try {
					Files.copy(file, file.resolveSibling("archive.json.broken"), StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException ignored) {
				}
			}
		}
		if (archive == null) archive = new Archive();
		ForgedInMemory.LOGGER.info("[Forged in Memory] Archive loaded: {} items ({})", archive.records.size(), file);
	}

	public static void save() {
		if (archive == null || file == null) return;
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling("archive.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(archive, w);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			dirty = false;
		} catch (IOException e) {
			ForgedInMemory.LOGGER.error("[Forged in Memory] Could not save the archive", e);
		}
	}

	public static boolean active() {
		return archive != null;
	}

	// ---- used by DevCommands (dev environment only)

	public static Archive archive() {
		return archive;
	}

	public static void markChanged(ItemRecord r) {
		touch(r);
	}

	public static void markAllDirty() {
		for (ItemRecord r : archive.records.values()) dirtyIds.add(r.id);
		dirty = true;
	}

	public static void sendFull(ServerPlayer p) {
		dirty = true;
		fullSync(p);
	}

	public static void killForTest(ItemRecord r, String cause, ServerPlayer p) {
		String action = null, target = null;
		if ("broke".equals(cause)) {
			action = "mining";
			target = "minecraft:deepslate_diamond_ore";
		} else if ("explosion".equals(cause)) {
			target = "minecraft:creeper";
		}
		ItemRecord.Death d = newDeath(r, cause, action, target, dimKey(p.level()), ipos(p.position()));
		d.whileMissing = "lost".equals(cause);
		die(r, d, p.position().add(p.getLookAngle().scale(2)));
	}

	// ------------------------------------------------------------------ helpers

	private static long now() {
		return System.currentTimeMillis();
	}

	private static long day() {
		return server == null ? 0 : server.overworld().getOverworldClockTime() / 24000L;
	}

	private static long gameTime() {
		return server == null ? 0 : server.overworld().getGameTime();
	}

	private static String dimKey(net.minecraft.world.level.Level l) {
		return l.dimension().identifier().toString();
	}

	public static String entityKey(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	private static String blockKey(BlockState s) {
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
	}

	private static int[] ipos(Vec3 v) {
		return new int[] {(int) Math.floor(v.x), (int) Math.floor(v.y), (int) Math.floor(v.z)};
	}

	private static boolean ignored(Player p) {
		return p == null || p.isCreative() || p.isSpectator();
	}

	private static ItemRecord rec(ItemStack s) {
		if (archive == null || !Ids.trackable(s)) return null;
		String id = Ids.get(s);
		return id == null ? null : archive.records.get(id);
	}

	private static void touch(ItemRecord r) {
		dirtyIds.add(r.id);
		dirty = true;
	}

	private static void setAction(Player p, String type, String target) {
		if (server == null || ignored(p)) return;
		lastAction.put(p.getUUID(), new Action(type, target, server.getTickCount()));
	}

	private static Action recentAction(Player p) {
		if (p == null || server == null) return null;
		Action a = lastAction.get(p.getUUID());
		if (a == null || server.getTickCount() - a.tick > 100) return null;
		return a;
	}

	// ------------------------------------------------------------------ tick + scan

	private static void tick(MinecraftServer s) {
		if (archive == null) return;
		long t = s.getTickCount();
		attributions.removeIf(a -> t - a.tick > 2);
		if (t % 10 == 0) {
			boolean second = t % 20 == 0;
			for (ServerPlayer p : s.getPlayerList().getPlayers()) scan(p, second);
		}
		if (t % 20 == 0) {
			checkMissing();
			pending.values().forEach(q -> q.removeIf(pb -> t - pb.tick > 200));
		}
		if (t % 40 == 0) flushSync();
		if (t % 1200 == 0 && dirty) save();
	}

	private static void scan(ServerPlayer p, boolean second) {
		UUID uuid = p.getUUID();
		if (ignored(p)) {
			lastPos.remove(uuid);
			return;
		}
		String owner = uuid.toString();
		boolean firstScan = !archive.knownPlayers.contains(owner);
		Inventory inv = p.getInventory();
		Set<String> seen = new HashSet<>();

		double moved = 0;
		Second sec = null;
		if (second) {
			Vec3 cur = p.position();
			Vec3 prev = lastPos.put(uuid, cur);
			String dim = dimKey(p.level());
			String prevDim = lastDim.put(uuid, dim);
			if (prev != null && dim.equals(prevDim)) {
				moved = cur.distanceTo(prev);
				if (moved > 100) moved = 0; // teleport
			}
			String mode = p.isFallFlying() ? "flying" : p.isPassenger() ? "riding" : (p.isSwimming() || p.isInWater()) ? "swimming"
					: p.isSprinting() ? "sprinting" : p.isCrouching() ? "sneaking" : "walking";
			int[] now = readStats(p);
			int[] before = lastStats.put(uuid, now);
			int[] delta = new int[now.length];
			if (before != null) for (int k = 0; k < now.length; k++) delta[k] = Math.max(0, now[k] - before[k]);
			if (p.isFallFlying()) flightRun.merge(uuid, 1, Integer::sum);
			else flightRun.remove(uuid);
			boolean sunset = false, fullMoon = false;
			if (p.level().dimension() == Level.OVERWORLD) {
				boolean dark = p.level().isDarkOutside();
				Boolean was = wasDark.put(uuid, dark);
				if (was != null && !was && dark) {
					sunset = true;
					fullMoon = Math.floorMod(p.level().getOverworldClockTime() / 24000L, 8L) == 0;
				}
			}
			sec = new Second(moved, mode, delta, p.isFallFlying(), sunset, fullMoon);
		}

		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (!Ids.trackable(s)) continue;
			String id = Ids.get(s);
			ItemRecord r = id == null ? null : archive.records.get(id);
			if (id == null) {
				id = Ids.newId();
				Ids.set(s, id);
				r = birth(p, s, id, firstScan ? "already_owned" : null);
			} else if (r == null) {
				r = birth(p, s, id, "other_world");
			} else if (!seen.add(id)) {
				String twinOf = id;
				id = Ids.newId();
				Ids.set(s, id);
				r = birth(p, s, id, "twin");
				r.parents.add(new Link("twin", twinOf, 0, null));
			}
			seen.add(id);
			update(r, s, p, i, sec);
		}
		if (firstScan) {
			archive.knownPlayers.add(owner);
			dirty = true;
		}
	}

	private static ItemRecord birth(ServerPlayer p, ItemStack s, String id, String forcedHow) {
		String item = Ids.itemKey(s.getItem());
		String how = forcedHow;
		List<Link> parents = new ArrayList<>();
		String maker = null;
		if (how == null) {
			Deque<Pending> q = pending.get(p.getUUID());
			if (q != null) {
				for (Iterator<Pending> it = q.iterator(); it.hasNext();) {
					Pending pb = it.next();
					if (pb.item.equals(item)) {
						it.remove();
						how = pb.how;
						parents.addAll(pb.parents);
						maker = pb.maker;
						break;
					}
				}
			}
		}
		if (how == null) how = menuOrigin(p.containerMenu, p);

		ItemRecord r = new ItemRecord();
		r.id = id;
		r.item = item;
		r.kind = Ids.kind(s);
		r.name = s.getHoverName().getString();
		r.owner = p.getUUID().toString();
		r.ownerName = p.getName().getString();
		r.owners.add(r.owner);
		r.bornAt = now();
		r.bornDay = day();
		r.bornWorld = server.getWorldData().getLevelName();
		r.bornDim = dimKey(p.level());
		r.bornPos = ipos(p.position());
		r.bornBiome = p.level().getBiome(p.blockPosition()).unwrapKey().map(k -> k.identifier().toString()).orElse("");
		r.how = how;
		r.maker = maker;
		r.parents.addAll(parents);
		r.lastDamage = s.getDamageValue();
		r.maxDamage = s.getMaxDamage();
		r.dims.add(r.bornDim);
		long clock = p.level().getOverworldClockTime();
		r.bornTimeOfDay = Math.floorMod(clock, 24000L);
		r.bornMoon = (int) Math.floorMod(clock / 24000L, 8L);
		r.bornWeather = p.level().isThundering() ? "thunder" : p.level().isRaining() ? "rain" : "clear";

		// Successor: the last item of this kind that died gets this one as its heir.
		if (!"already_owned".equals(how) && !"twin".equals(how) && !"other_world".equals(how)) {
			Map<String, String> heirs = archive.heirs.get(r.owner);
			if (heirs != null) {
				String dead = heirs.remove(r.kind);
				if (dead != null && archive.records.containsKey(dead)) r.parents.add(new Link("successor", dead, 0, null));
			}
		}
		Component custom = s.get(DataComponents.CUSTOM_NAME);
		if (custom != null) {
			r.customName = custom.getString();
			r.names.add(r.customName);
		}
		r.enchants = enchantMap(s);
		r.event(r.bornDay, "born", how, maker);
		archive.records.put(id, r);
		touch(r);
		return r;
	}

	private static String menuOrigin(AbstractContainerMenu menu, Player p) {
		if (menu == null || menu == p.inventoryMenu) return "picked_up";
		if (menu instanceof MerchantMenu) return "traded";
		String n = menu.getClass().getSimpleName();
		return switch (n) {
			case "ChestMenu" -> "container:chest";
			case "ShulkerBoxMenu" -> "container:shulker_box";
			case "HopperMenu" -> "container:hopper";
			case "DispenserMenu" -> "container:dispenser";
			case "CrafterMenu" -> "container:crafter";
			case "HorseInventoryMenu" -> "container:saddlebag";
			default -> "container:container";
		};
	}

	private static Map<String, Integer> enchantMap(ItemStack s) {
		Map<String, Integer> m = new LinkedHashMap<>();
		var ench = s.getEnchantments();
		for (Holder<Enchantment> h : ench.keySet()) {
			String key = h.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
			m.put(key, ench.getLevel(h));
		}
		return m;
	}

	private static int[] readStats(ServerPlayer p) {
		var st = p.getStats();
		return new int[] {
				st.getValue(Stats.CUSTOM, Stats.JUMP),
				st.getValue(Stats.CUSTOM, Stats.ANIMALS_BRED),
				st.getValue(Stats.CUSTOM, Stats.TRADED_WITH_VILLAGER),
				st.getValue(Stats.CUSTOM, Stats.SLEEP_IN_BED),
				st.getValue(Stats.CUSTOM, Stats.RAID_WIN),
				st.getValue(Stats.CUSTOM, Stats.EAT_CAKE_SLICE),
				st.getValue(Stats.CUSTOM, Stats.PLAY_RECORD),
				st.getValue(Stats.CUSTOM, Stats.BELL_RING),
				st.getValue(Stats.CUSTOM, Stats.TARGET_HIT),
				p.totalExperience,
				st.getValue(Stats.ITEM_USED, Items.FIREWORK_ROCKET)};
	}

	private static String biomeKey(Level level, BlockPos pos) {
		return level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
	}

	private static void update(ItemRecord r, ItemStack s, ServerPlayer p, int slot, Second sec) {
		boolean second = sec != null;
		double moved = second ? sec.moved : 0;
		long d = day();
		boolean changed = false;
		String owner = p.getUUID().toString();
		if (!owner.equals(r.owner)) {
			r.event(d, "owner", p.getName().getString(), r.ownerName);
			r.owner = owner;
			r.ownerName = p.getName().getString();
			if (!r.owners.contains(owner)) r.owners.add(owner);
			changed = true;
		}
		if (r.isMissing()) {
			r.status = ItemRecord.ALIVE;
			r.event(d, "recovered", p.getName().getString(), null);
			changed = true;
		} else if (r.isDead()) {
			r.status = ItemRecord.ALIVE;
			r.event(d, "revived", r.death != null ? r.death.cause : null, null);
			r.death = null;
			changed = true;
		}
		String item = Ids.itemKey(s.getItem());
		if (!item.equals(r.item)) {
			r.event(d, "upgraded", r.item, item);
			r.item = item;
			r.kind = Ids.kind(s);
			r.maxDamage = s.getMaxDamage();
			changed = true;
		}
		Component custom = s.get(DataComponents.CUSTOM_NAME);
		String cn = custom == null ? null : custom.getString();
		if (cn != null && !cn.equals(r.customName)) {
			r.event(d, "renamed", cn, r.customName);
			r.names.add(cn);
			changed = true;
		}
		r.customName = cn;
		String hover = s.getHoverName().getString();
		if (!hover.equals(r.name)) {
			r.name = hover;
			changed = true;
		}
		Map<String, Integer> ench = enchantMap(s);
		if (!ench.equals(r.enchants)) {
			for (Map.Entry<String, Integer> e : ench.entrySet()) {
				Integer old = r.enchants.get(e.getKey());
				if (old == null || old < e.getValue()) r.event(d, "enchanted", e.getKey(), String.valueOf(e.getValue()));
			}
			for (String k : r.enchants.keySet()) {
				if (!ench.containsKey(k)) r.event(d, "disenchanted", k, null);
			}
			r.enchants = ench;
			changed = true;
		}
		int dmg = s.getDamageValue();
		if (dmg < r.lastDamage) {
			Long anvil = anvilTouched.get(r.id);
			if (anvil == null || gameTime() - anvil > 40) r.add("mendingRepaired", r.lastDamage - dmg);
			changed = true;
		}
		r.lastDamage = dmg;

		if (second) {
			// Player inventory: 0-35 main, 36 feet, 37 legs, 38 chest, 39 head, 40 offhand
			boolean held = slot == inv(p).getSelectedSlot() || slot == Inventory.SLOT_OFFHAND;
			boolean worn = slot >= 36 && slot <= 39;
			if (held || worn) {
				r.add(worn ? "wornSec" : "heldSec", 1);
				r.add("distance", moved);
				r.min("closestCall", p.getHealth());
				String dim = dimKey(p.level());
				if (!r.dims.contains(dim)) {
					r.dims.add(dim);
					r.event(d, "dimension", dim, null);
				}
				if (worn && "elytra".equals(r.kind) && p.isFallFlying()) {
					r.add("flightSec", 1);
					r.add("flightDist", moved);
					r.max("topSpeed", moved);
					r.max("longestFlight", flightRun.getOrDefault(p.getUUID(), 0));
					r.max("highestFlight", p.getY());
					r.add("rockets", sec.statDelta[10]);
				}
				if (moved > 0) r.distanceBy.merge(sec.mode, moved, Double::sum);
				r.timeIn.merge(dim, 1.0, Double::sum);
				ServerLevel lvl = (ServerLevel) p.level();
				BlockPos bp = p.blockPosition();
				if (p.isUnderWater()) r.add("underwaterSec", 1);
				if (p.isInLava()) r.add("lavaSec", 1);
				if (p.isOnFire()) r.add("fireSec", 1);
				if (lvl.isRainingAt(bp.above())) r.add(lvl.isThundering() ? "stormSec" : "rainSec", 1);
				if (lvl.dimension() == Level.OVERWORLD && lvl.isDarkOutside()) r.add("nightSec", 1);
				if (lvl.getMaxLocalRawBrightness(bp) == 0) r.add("darkSec", 1);
				r.max("highestVisited", p.getY());
				r.min("lowestVisited", p.getY());
				String biome = biomeKey(lvl, bp);
				if (!r.biomes.contains(biome)) r.biomes.add(biome);
				for (int k = 0; k < STAT_KEYS.length - 1; k++) {
					if (sec.statDelta[k] > 0) r.add(STAT_KEYS[k], sec.statDelta[k]);
				}
				if (sec.sunset) {
					r.add("sunsets", 1);
					if (sec.fullMoon) r.add("fullMoons", 1);
				}
				if (r.bornPos != null && dim.equals(r.bornDim)) {
					r.max("farthestFromHome", Math.hypot(p.getX() - r.bornPos[0], p.getZ() - r.bornPos[2]));
				}
				changed = true;
			}
			r.lastSeen = now();
			r.lastSeenDim = dimKey(p.level());
			r.lastSeenPos = ipos(p.position());
		}
		if (changed) touch(r);
	}

	private static Inventory inv(ServerPlayer p) {
		return p.getInventory();
	}

	private static void checkMissing() {
		long gt = gameTime();
		for (ItemRecord r : archive.records.values()) {
			if (r.isMissing() && gt - r.missingSince > dev.k3ytel.forgedinmemory.core.FimConfig.get().missingMinutes * 1200L) {
				ItemRecord.Death death = newDeath(r, "lost", null, null, r.lastSeenDim, r.lastSeenPos);
				death.whileMissing = true;
				die(r, death, null);
			}
		}
	}

	// ------------------------------------------------------------------ deaths

	private static ItemRecord.Death newDeath(ItemRecord r, String cause, String action, String target, String dim, int[] pos) {
		ItemRecord.Death d = new ItemRecord.Death();
		d.cause = cause;
		d.action = action;
		d.target = target;
		d.at = now();
		d.day = day();
		d.dim = dim;
		d.pos = pos;
		d.ownerName = r.ownerName;
		d.whileMissing = r.isMissing();
		return d;
	}

	private static void die(ItemRecord r, ItemRecord.Death death, Vec3 where) {
		if (r.isDead()) return;
		try {
			if (server != null && death.dim != null && death.pos != null) {
				for (ServerLevel lvl : server.getAllLevels()) {
					if (!dimKey(lvl).equals(death.dim)) continue;
					BlockPos bp = new BlockPos(death.pos[0], death.pos[1], death.pos[2]);
					death.biome = biomeKey(lvl, bp);
					for (ServerPlayer other : lvl.players()) {
						if (other.getUUID().toString().equals(r.owner)) continue;
						if (other.position().distanceTo(Vec3.atCenterOf(bp)) <= 48) death.witnesses.add(other.getName().getString());
					}
				}
			}
		} catch (Exception e) {
			ForgedInMemory.LOGGER.warn("[Forged in Memory] Could not look up the death place", e);
		}
		r.status = ItemRecord.DEAD;
		r.death = death;
		r.event(death.day, "died", death.cause, death.target);
		archive.heirs.computeIfAbsent(r.owner, k -> new HashMap<>()).put(r.kind, r.id);
		touch(r);
		ForgedInMemory.LOGGER.info("[Forged in Memory] {} ({}) died: {} {}", r.name, r.id, death.cause, death.target == null ? "" : death.target);
		// Make sure the client has the final record before the death notice.
		flushSync();
		ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(UUID.fromString(r.owner));
		if (p != null && ServerPlayNetworking.canSend(p, Net.DeathPayload.TYPE)) {
			Vec3 at = where != null ? where : p.position();
			String dim = death.dim != null ? death.dim : dimKey(p.level());
			ServerPlayNetworking.send(p, new Net.DeathPayload(r.id, dim, at.x, at.y, at.z));
		}
	}

	/** Mixin hook: ItemStack.applyDamage (durability goes down; may break). */
	public static void onDurability(ItemStack s, int newDamage, ServerPlayer player) {
		ItemRecord r = rec(s);
		if (r == null) return;
		if (player != null && ignored(player)) return;
		int used = newDamage - s.getDamageValue();
		if (used > 0) {
			r.add("durabilityUsed", used);
			Action a = recentAction(player);
			boolean mining = a != null && "mining".equals(a.type) && server.getTickCount() - a.tick <= 1;
			if (!mining) {
				switch (r.kind) {
					case "hoe" -> r.add("tilled", 1);
					case "shears" -> r.add("sheared", 1);
					case "flint_and_steel" -> r.add("fires", 1);
					case "brush" -> r.add("brushed", 1);
					case "on_a_stick" -> r.add("boosts", 1);
					default -> {
					}
				}
			}
		}
		r.lastDamage = newDamage;
		r.maxDamage = s.getMaxDamage();
		touch(r);
		if (newDamage >= s.getMaxDamage()) {
			Action a = recentAction(player);
			Vec3 where = player != null ? player.position() : null;
			ItemRecord.Death d = newDeath(r, "broke", a == null ? null : a.type, a == null ? null : a.target,
					player != null ? dimKey(player.level()) : r.lastSeenDim, where != null ? ipos(where) : r.lastSeenPos);
			die(r, d, where);
		}
	}

	/** Mixin hook: ItemEntity.hurtServer (remember what's hurting a dropped item). */
	public static void onItemEntityHurt(ItemEntity e, DamageSource source) {
		if (archive != null) itemDamage.put(e, source);
	}

	/** Mixin hook: ItemStack.onDestroyed (a dropped item burned / exploded / got pricked). */
	public static void onItemDestroyed(ItemStack s, ItemEntity e) {
		ItemRecord r = rec(s);
		if (r == null) return;
		DamageSource src = itemDamage.get(e);
		String cause = "destroyed";
		String target = null;
		if (src != null) {
			if (src.is(DamageTypes.LAVA)) cause = "lava";
			else if (src.is(DamageTypes.IN_FIRE) || src.is(DamageTypes.ON_FIRE) || src.is(DamageTypes.CAMPFIRE)) cause = "fire";
			else if (src.is(DamageTypes.CACTUS)) cause = "cactus";
			else if (src.is(DamageTypes.EXPLOSION) || src.is(DamageTypes.PLAYER_EXPLOSION)) {
				cause = "explosion";
				if (src.getEntity() != null) target = entityKey(src.getEntity());
				else if (src.getDirectEntity() != null) target = entityKey(src.getDirectEntity());
			} else if (src.is(DamageTypes.LIGHTNING_BOLT)) cause = "lightning";
		}
		die(r, newDeath(r, cause, null, target, dimKey(e.level()), ipos(e.position())), e.position());
	}

	// ------------------------------------------------------------------ mining

	private static void beforeBreak(Player player, BlockPos pos, BlockState state) {
		if (archive == null || ignored(player)) return;
		setAction(player, "mining", blockKey(state));
		String id = Ids.get(player.getMainHandItem());
		if (id != null && Ids.trackable(player.getMainHandItem())) {
			breakingTool.put(player.getUUID(), id);
			// Drops spawn before PlayerBlockBreakEvents.AFTER fires, so link them to the tool now.
			attributions.add(new Attr(dimKey(player.level()), Vec3.atCenterOf(pos), id, server.getTickCount(), null));
		} else {
			breakingTool.remove(player.getUUID());
		}
	}

	private static void afterBreak(net.minecraft.world.level.Level level, Player player, BlockPos pos, BlockState state) {
		if (archive == null || ignored(player)) return;
		String id = breakingTool.remove(player.getUUID());
		if (id == null) return;
		ItemRecord r = archive.records.get(id);
		if (r == null) return;
		String bk = blockKey(state);
		String path = bk.substring(bk.indexOf(':') + 1);
		r.mined.merge(bk, 1L, Long::sum);
		r.minedPerDay.merge(day(), 1L, Long::sum);
		r.min("deepestY", pos.getY());
		r.max("highestY", pos.getY());
		if (r.firstBlock == null) r.firstBlock = bk;
		r.lastBlock = bk;
		if (path.endsWith("_ore") || path.equals("ancient_debris")) r.add("ores", 1);
		if (path.endsWith("diamond_ore")) r.add("diamonds", 1);
		if (path.equals("ancient_debris")) r.add("debris", 1);
		if (path.equals("spawner") || path.equals("trial_spawner")) r.add("spawners", 1);
		if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) r.add("crops", 1);
		if (path.endsWith("_log") || path.endsWith("_stem")) r.add("logs", 1);
		if (level.getMaxLocalRawBrightness(pos) == 0) r.add("darkMined", 1);
		r.minedBiome.merge(biomeKey(level, pos), 1L, Long::sum);
		r.minedDim.merge(dimKey(level), 1L, Long::sum);
		long t = server.getTickCount();
		long[] ses = digSessions.computeIfAbsent(id, k -> new long[] {0, -10000});
		ses[0] = t - ses[1] <= 100 ? ses[0] + 1 : 1;
		ses[1] = t;
		r.max("longestDig", ses[0]);
		ArrayDeque<Long> win = digWindow.computeIfAbsent(id, k -> new ArrayDeque<>());
		win.add(t);
		while (!win.isEmpty() && t - win.peekFirst() > 1200) win.poll();
		r.max("bestMinute", win.size());
		r.moment("mined", bk);
		long total = r.totalMined();
		for (long m : MINED_MILESTONES) if (total == m) r.event(day(), "milestone", "mined", String.valueOf(m));
		touch(r);
	}

	// ------------------------------------------------------------------ combat

	private static void afterDamage(LivingEntity entity, DamageSource source, float base, float taken, boolean blocked) {
		if (archive == null) return;
		if (source.getEntity() instanceof ServerPlayer attacker && !ignored(attacker) && entity != attacker) {
			ItemRecord r = rec(source.getWeaponItem());
			if (r != null) {
				r.add("damageDealt", taken);
				r.max("biggestHit", taken);
				Entity direct = source.getDirectEntity();
				if (direct instanceof AbstractArrow && !(direct instanceof ThrownTrident)) {
					r.add("hits", 1);
					r.max("longestShot", attacker.position().distanceTo(entity.position()));
				} else if (direct instanceof ThrownTrident) {
					r.add("tridentHits", 1);
					r.max("longestShot", attacker.position().distanceTo(entity.position()));
				} else if (direct == attacker && attacker.fallDistance > 0 && !attacker.onGround() && !attacker.isSprinting()) {
					r.add("crits", 1);
				}
				if (entity.isDeadOrDying() && taken >= entity.getMaxHealth() * 0.99f) r.killKinds.merge("onehit", 1L, Long::sum);
				r.add("hitsLanded", 1);
				touch(r);
			}
		}
		if (entity instanceof ServerPlayer victim && !ignored(victim)) {
			if (blocked) {
				ItemRecord shield = rec(victim.getUseItem());
				if (shield != null) {
					shield.add("blocked", 1);
					shield.add("blockedDamage", base);
					shield.max("biggestBlocked", base);
					if (source.getDirectEntity() instanceof Projectile) shield.add("projectilesBlocked", 1);
					else if (source.is(DamageTypeTags.IS_EXPLOSION)) shield.add("explosionsBlocked", 1);
					else shield.add("meleeBlocked", 1);
					String by = source.getEntity() != null ? entityKey(source.getEntity()) : "dmg:" + damageKind(source);
					shield.hitBy.merge(by, (double) base, Double::sum);
					shield.moment("blocked", by);
					touch(shield);
				}
			} else {
				List<ItemRecord> worn = new ArrayList<>();
				for (EquipmentSlot es : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
					ItemRecord r = rec(victim.getItemBySlot(es));
					if (r != null) worn.add(r);
				}
				double absorbed = Math.max(0, base - taken);
				String kind = damageKind(source);
				String by = source.getEntity() != null ? entityKey(source.getEntity()) : "dmg:" + kind;
				boolean saved = !victim.isDeadOrDying() && victim.getHealth() <= 4 && absorbed > 0;
				ItemRecord heldR = rec(victim.getMainHandItem());
				if (heldR != null) {
					heldR.hitBy.merge(by, (double) taken, Double::sum);
					heldR.moment("hit", by);
				}
				for (ItemRecord r : worn) {
					r.hitBy.merge(by, (double) taken, Double::sum);
					r.moment("hit", by);
					if (saved) r.add("savedLife", 1);
					if ("fall".equals(kind)) r.max("biggestFall", base + 3);
					r.add("hitsTaken", 1);
					r.add("absorbed", absorbed / worn.size());
					r.absorbedBy.merge(kind, absorbed / worn.size(), Double::sum);
					r.max("worstHit", taken);
					r.min("closestCall", victim.getHealth());
					if ("fall".equals(kind)) r.add("fallsTaken", 1);
					if (saved) r.event(day(), "saved", by, null);
					if ("explosion".equals(kind) && !victim.isDeadOrDying()) r.add("explosionsSurvived", 1);
					touch(r);
				}
				ItemRecord held = rec(victim.getMainHandItem());
				if (held != null) {
					held.min("closestCall", victim.getHealth());
					touch(held);
				}
			}
			if (source.is(DamageTypes.FLY_INTO_WALL)) {
				ItemRecord ely = rec(victim.getItemBySlot(EquipmentSlot.CHEST));
				if (ely != null) {
					ely.add("crashes", 1);
					touch(ely);
				}
			}
		}
	}

	private static String damageKind(DamageSource source) {
		if (source.is(DamageTypeTags.IS_FALL)) return "fall";
		if (source.is(DamageTypeTags.IS_EXPLOSION)) return "explosion";
		if (source.is(DamageTypeTags.IS_FIRE)) return "fire";
		if (source.is(DamageTypeTags.IS_PROJECTILE)) return "projectile";
		if (source.is(DamageTypeTags.IS_DROWNING)) return "drowning";
		if (source.is(DamageTypeTags.IS_FREEZING)) return "freezing";
		if (source.is(DamageTypeTags.IS_LIGHTNING)) return "lightning";
		if (source.getEntity() != null) return "melee";
		return "other";
	}

	private static void afterDeath(LivingEntity entity, DamageSource source) {
		if (archive == null) return;
		if (source.getEntity() instanceof ServerPlayer killer && !ignored(killer) && entity != killer) {
			ItemStack weapon = source.getWeaponItem();
			ItemRecord r = rec(weapon);
			if (r != null) {
				String key = entityKey(entity);
				r.kills.merge(key, 1L, Long::sum);
				r.lastKill = key;
				EntityType<?> t = entity.getType();
				if (t == EntityTypes.ENDER_DRAGON || t == EntityTypes.WITHER || t == EntityTypes.WARDEN || t == EntityTypes.ELDER_GUARDIAN) {
					r.add("bosses", 1);
					r.event(day(), "boss", key, null);
				}
				long gt = gameTime();
				long[] st = streaks.computeIfAbsent(r.id, k -> new long[] {0, -10000});
				st[0] = gt - st[1] <= 200 ? st[0] + 1 : 1;
				st[1] = gt;
				r.max("bestStreak", st[0]);
				if (r.firstKill == null) {
					r.firstKill = key;
					r.firstKillDay = day();
				}
				r.max("longestKill", killer.position().distanceTo(entity.position()));
				Level lvl = entity.level();
				if (lvl.dimension() == Level.OVERWORLD && lvl.isDarkOutside()) r.killKinds.merge("night", 1L, Long::sum);
				if (!killer.onGround() && !killer.isPassenger() && !killer.isInWater() && !killer.isFallFlying()) r.killKinds.merge("midair", 1L, Long::sum);
				if (killer.isFallFlying()) r.killKinds.merge("flying", 1L, Long::sum);
				if (killer.isOnFire()) r.killKinds.merge("onfire", 1L, Long::sum);
				if (killer.isUnderWater()) r.killKinds.merge("underwater", 1L, Long::sum);
				if (killer.getHealth() <= 6) r.killKinds.merge("clutch", 1L, Long::sum);
				if (killer.getLastHurtByMob() == entity) r.killKinds.merge("revenge", 1L, Long::sum);
				if (entity.isBaby()) r.killKinds.merge("baby", 1L, Long::sum);
				if (entity instanceof Player) r.killKinds.merge("player", 1L, Long::sum);
				else if (t.getCategory() == MobCategory.MONSTER) r.killKinds.merge("hostile", 1L, Long::sum);
				else r.killKinds.merge("animal", 1L, Long::sum);
				if (entity.hasCustomName() && r.namedVictims.size() < 30) r.namedVictims.add(entity.getCustomName().getString());
				r.killsPerDay.merge(day(), 1L, Long::sum);
				r.moment("killed", key);
				long kt = r.totalKills();
				for (long m : KILL_MILESTONES) if (kt == m) r.event(day(), "milestone", "kills", String.valueOf(m));
				touch(r);
			}
		}
		if (entity instanceof ServerPlayer p && !ignored(p)) {
			dyingTick.put(p.getUUID(), (long) server.getTickCount());
			String killer = source.getEntity() != null ? entityKey(source.getEntity()) : source.getMsgId();
			for (EquipmentSlot es : EquipmentSlot.values()) {
				ItemRecord r = rec(p.getItemBySlot(es));
				if (r == null) continue;
				r.add(es.isArmor() ? "deathsWorn" : "deathsHeld", 1);
				r.event(day(), "death_witness", killer, null);
				touch(r);
			}
			streaks.clear();
		}
	}

	// ------------------------------------------------------------------ entities

	private static void onLoad(Entity e, ServerLevel level) {
		if (archive == null) return;
		long t = server.getTickCount();
		if (e instanceof ItemEntity ie) {
			if (ie.getAge() > 0) return; // loaded from disk, not just spawned
			String dim = dimKey(level);
			Vec3 pos = ie.position();
			for (Attr a : attributions) {
				if (a.dim.equals(dim) && a.pos.distanceToSqr(pos) <= 4.0) {
					if (a.tool != null) entityTool.put(ie, a.tool);
					if (a.origin != null) entityOrigin.put(ie, a.origin);
					break;
				}
			}
			for (Map.Entry<FishingHook, String> h : hookRod.entrySet()) {
				FishingHook hook = h.getKey();
				if (hook.level() == level && hook.position().distanceToSqr(pos) <= 4.0) {
					entityOrigin.put(ie, "fished");
					ItemRecord rod = archive.records.get(h.getValue());
					if (rod != null) {
						ItemStack c = ie.getItem();
						rod.caught.merge(Ids.itemKey(c.getItem()), 1L, Long::sum);
						if (c.is(ItemTags.FISHES)) rod.add("fish", 1);
						else if (c.is(Items.ENCHANTED_BOOK) || c.is(Items.NAME_TAG) || c.is(Items.SADDLE) || c.is(Items.NAUTILUS_SHELL)
								|| c.is(Items.BOW) || c.is(Items.FISHING_ROD)) rod.add("treasure", 1);
						else rod.add("junk", 1);
						touch(rod);
					}
					break;
				}
			}
			ItemRecord r = rec(ie.getItem());
			if (r != null && r.isDead() == false) {
				Long dying = r.owner == null ? null : dyingTick.get(UUID.fromString(r.owner));
				r.lastSeenDim = dim;
				r.lastSeenPos = ipos(pos);
				if (dying != null && t - dying <= 2) {
					r.status = ItemRecord.MISSING;
					r.missingSince = gameTime();
					r.event(day(), "missing", dim, null);
				} else {
					r.event(day(), "dropped", dim, null);
					r.add("drops", 1);
				}
				r.moment("dropped", dim);
				touch(r);
			}
		} else if (e instanceof ThrownTrident tt) {
			ItemRecord r = rec(tt.getWeaponItem());
			if (r != null) {
				r.add("throws", 1);
				touch(r);
			}
		} else if (e instanceof AbstractArrow arrow) {
			if (arrow.getOwner() instanceof ServerPlayer p && !ignored(p)) {
				ItemRecord r = rec(arrow.getWeaponItem());
				if (r != null) {
					r.add("shots", 1);
					touch(r);
				}
			}
		} else if (e instanceof FishingHook hook) {
			Player p = hook.getPlayerOwner();
			if (p != null && !ignored(p)) {
				ItemStack rod = p.getMainHandItem();
				ItemRecord r = rec(rod);
				if (r == null || !"fishing_rod".equals(r.kind)) r = rec(p.getOffhandItem());
				if (r != null && "fishing_rod".equals(r.kind)) {
					r.add("casts", 1);
					hookRod.put(hook, r.id);
					touch(r);
				}
			}
		}
	}

	private static void onUnload(Entity e, ServerLevel level) {
		if (archive == null) return;
		if (e instanceof FishingHook hook) {
			hookRod.remove(hook);
			return;
		}
		Entity.RemovalReason reason = e.getRemovalReason();
		if (reason == null) return;
		ItemStack s;
		int age = 0;
		if (e instanceof ItemEntity ie) {
			s = ie.getItem();
			age = ie.getAge();
		} else if (e instanceof ThrownTrident tt) {
			s = tt.getWeaponItem();
		} else {
			return;
		}
		ItemRecord r = rec(s);
		if (r == null || r.isDead()) return;
		if (e instanceof ThrownTrident && !(e.getY() < level.getMinY())) return;
		if (reason == Entity.RemovalReason.DISCARDED) {
			if (e.getY() < level.getMinY()) {
				die(r, newDeath(r, "void", null, null, dimKey(level), ipos(e.position())), e.position());
			} else if (e instanceof ItemEntity && age >= 5999) {
				die(r, newDeath(r, "despawned", null, null, dimKey(level), ipos(e.position())), e.position());
			}
		} else if (reason == Entity.RemovalReason.KILLED) {
			die(r, newDeath(r, "destroyed", null, null, dimKey(level), ipos(e.position())), e.position());
		}
	}

	/** Mixin hook: ItemEntity.playerTouch, after it ran. pickedUp = how many items went into the player. */
	public static void onPickup(ItemEntity e, Player p, ItemStack stackBefore, int pickedUp) {
		if (archive == null || pickedUp <= 0 || !(p instanceof ServerPlayer sp) || ignored(sp)) return;
		if (Ids.trackable(stackBefore) && Ids.get(stackBefore) == null) {
			String origin = entityOrigin.getOrDefault(e, "picked_up");
			pending.computeIfAbsent(sp.getUUID(), k -> new ArrayDeque<>())
					.add(new Pending(Ids.itemKey(stackBefore.getItem()), origin, List.of(), null, server.getTickCount()));
		}
		String tool = entityTool.get(e);
		Ids.Family f = Ids.family(stackBefore);
		if (tool != null && f != null) {
			List<Archive.LedgerEntry> q = archive.ledger.computeIfAbsent(sp.getUUID().toString(), k -> new HashMap<>())
					.computeIfAbsent(f.name(), k -> new ArrayList<>());
			double units = f.weight() * pickedUp;
			if (!q.isEmpty() && q.get(q.size() - 1).tool.equals(tool)) q.get(q.size() - 1).units += units;
			else q.add(new Archive.LedgerEntry(tool, units));
			double total = 0;
			for (Archive.LedgerEntry le : q) total += le.units;
			while (total > 4096 && !q.isEmpty()) total -= q.remove(0).units;
			dirty = true;
			ForgedInMemory.LOGGER.info("[Forged in Memory] Ledger: {} x{} ({}) gathered by {}", Ids.itemKey(stackBefore.getItem()), pickedUp, f.name(), tool);
		}
	}

	// ------------------------------------------------------------------ crafting / anvil / smithing

	/** Take units of a material family from the player's ledger (oldest first). */
	private static Map<String, Double> takeLedger(String player, String family, double units) {
		Map<String, Double> out = new LinkedHashMap<>();
		Map<String, List<Archive.LedgerEntry>> fams = archive.ledger.get(player);
		if (fams == null) return out;
		List<Archive.LedgerEntry> q = fams.get(family);
		if (q == null) return out;
		while (units > 1e-6 && !q.isEmpty()) {
			Archive.LedgerEntry le = q.get(0);
			double take = Math.min(units, le.units);
			out.merge(le.tool, take, Double::sum);
			le.units -= take;
			units -= take;
			if (le.units <= 1e-6) q.remove(0);
		}
		dirty = true;
		return out;
	}

	private static void addMaterialParents(List<Link> parents, String player, Map<String, Double> familyUnits) {
		for (Map.Entry<String, Double> fu : familyUnits.entrySet()) {
			for (Map.Entry<String, Double> t : takeLedger(player, fu.getKey(), fu.getValue()).entrySet()) {
				if (archive.records.containsKey(t.getKey())) parents.add(new Link("material", t.getKey(), t.getValue(), fu.getKey()));
			}
		}
	}

	/** Mixin hook: ResultSlot.onTake (crafting grid or table). */
	public static void onCraft(Player player, ItemStack result, List<ItemStack> inputs) {
		if (archive == null || !(player instanceof ServerPlayer sp) || ignored(sp) || !Ids.trackable(result)) return;
		String owner = sp.getUUID().toString();
		List<Link> parents = new ArrayList<>();
		Map<String, Double> fam = new LinkedHashMap<>();
		for (ItemStack in : inputs) {
			if (in.isEmpty()) continue;
			ItemRecord ir = rec(in);
			if (ir != null) {
				parents.add(new Link("merge", ir.id, 0, null));
				ItemRecord.Death d = newDeath(ir, "merged", null, null, dimKey(sp.level()), ipos(sp.position()));
				d.into = Ids.itemKey(result.getItem());
				die(ir, d, sp.position());
				continue;
			}
			Ids.Family f = Ids.family(in);
			if (f != null) fam.merge(f.name(), f.weight(), Double::sum);
		}
		addMaterialParents(parents, owner, fam);
		ForgedInMemory.LOGGER.info("[Forged in Memory] Crafted {}: {} family links, materials {}", Ids.itemKey(result.getItem()), parents.size(), fam);
		pending.computeIfAbsent(sp.getUUID(), k -> new ArrayDeque<>())
				.add(new Pending(Ids.itemKey(result.getItem()), "crafted", parents, sp.getName().getString(), server.getTickCount()));
	}

	/** Mixin hook: AnvilMenu.onTake. */
	public static void onAnvil(Player player, Container inputs, ItemStack result, int repairItemCount, int levels) {
		if (archive == null || !(player instanceof ServerPlayer sp) || ignored(sp)) return;
		ItemStack base = inputs.getItem(0);
		ItemStack add = inputs.getItem(1);
		ItemRecord r = rec(base);
		if (r == null) return;
		long d = day();
		if (levels > 0) r.add("levelsSpent", levels);
		r.add("anvilUses", 1);
		ItemRecord sac = rec(add);
		if (sac != null && sac != r) {
			r.parents.add(new Link("anvil", sac.id, 0, null));
			r.event(d, "absorbed", sac.id, sac.name);
			ItemRecord.Death death = newDeath(sac, "sacrificed", null, null, dimKey(sp.level()), ipos(sp.position()));
			death.into = r.id;
			die(sac, death, sp.position());
		} else {
			Ids.Family f = Ids.family(add);
			if (f != null && repairItemCount > 0) {
				Map<String, Double> fam = new LinkedHashMap<>();
				fam.put(f.name(), f.weight() * repairItemCount);
				addMaterialParents(r.parents, sp.getUUID().toString(), fam);
			}
		}
		if (Ids.trackable(result) && result.getDamageValue() < base.getDamageValue()) {
			r.add("repairsAnvil", 1);
			r.event(d, "repaired", "anvil", null);
			anvilTouched.put(r.id, gameTime());
			r.lastDamage = result.getDamageValue();
		}
		touch(r);
	}

	/** Mixin hook: EnchantmentMenu.clickMenuButton succeeded (enchanting table). */
	public static void onEnchantTable(Player player, ItemStack item, int levels) {
		if (archive == null || !(player instanceof ServerPlayer sp) || ignored(sp)) return;
		ItemRecord r = rec(item);
		if (r == null) return;
		r.add("levelsSpent", levels);
		r.add("tableEnchants", 1);
		touch(r);
	}

	/** Mixin hook: SmithingMenu.onTake (upgrade; the item keeps its ID through the copy). */
	public static void onSmith(Player player, Container inputs, ItemStack result) {
		if (archive == null || !(player instanceof ServerPlayer sp) || ignored(sp)) return;
		ItemRecord r = rec(inputs.getItem(1));
		if (r == null) return;
		Ids.Family f = Ids.family(inputs.getItem(2));
		if (f != null) {
			Map<String, Double> fam = new LinkedHashMap<>();
			fam.put(f.name(), f.weight());
			addMaterialParents(r.parents, sp.getUUID().toString(), fam);
			touch(r);
		}
	}

	// ------------------------------------------------------------------ sync

	private static void flushSync() {
		if (dirtyIds.isEmpty() || server == null) return;
		Map<String, List<ItemRecord>> byOwner = new HashMap<>();
		for (String id : dirtyIds) {
			ItemRecord r = archive.records.get(id);
			if (r != null && r.owner != null) byOwner.computeIfAbsent(r.owner, k -> new ArrayList<>()).add(r);
		}
		dirtyIds.clear();
		for (Map.Entry<String, List<ItemRecord>> e : byOwner.entrySet()) {
			ServerPlayer p;
			try {
				p = server.getPlayerList().getPlayer(UUID.fromString(e.getKey()));
			} catch (IllegalArgumentException ex) {
				continue;
			}
			if (p != null) send(p, false, e.getValue());
		}
	}

	private static void fullSync(ServerPlayer p) {
		if (archive == null || p == null) return;
		send(p, true, archive.recordsOf(p.getUUID().toString()));
	}

	private static void send(ServerPlayer p, boolean full, List<ItemRecord> list) {
		if (!ServerPlayNetworking.canSend(p, Net.SyncPayload.TYPE)) return;
		ServerPlayNetworking.send(p, new Net.SyncPayload(full, Net.gzip(GSON.toJson(list))));
	}
}
