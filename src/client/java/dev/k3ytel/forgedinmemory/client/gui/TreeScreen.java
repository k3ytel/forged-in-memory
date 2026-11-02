package dev.k3ytel.forgedinmemory.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.joml.Matrix3x2fStack;

import com.mojang.blaze3d.platform.InputConstants;

import dev.k3ytel.forgedinmemory.client.ClientArchive;
import dev.k3ytel.forgedinmemory.client.Names;
import dev.k3ytel.forgedinmemory.client.Obituary;
import dev.k3ytel.forgedinmemory.client.Titles;
import dev.k3ytel.forgedinmemory.core.FimConfig;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import dev.k3ytel.forgedinmemory.net.Net;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * The family tree as a living star chart (Skyrim style). Constellations (one per kind of gear) are
 * spread over a 2D sky around your Soul star, each with glowing outline art; every item is a star;
 * family links are arcs with light flowing from parent to child.
 */
public class TreeScreen extends Screen {
	private static final float ROW_H = 104f;
	private static final float SLOT_W = 128f;
	private static final int MAX_PER_ROW = 2;
	private static final int PANEL_W = 236;
	private static final float PAD = 190f;

	/** A constellation: kinds drawn together, colour, art texture and its home on the star chart. */
	public record Group(String title, int color, String art, float ax, float ay, String... kinds) {
		boolean has(String kind) {
			for (String k : kinds) if (k.equals(kind)) return true;
			return false;
		}
	}

	public static final Group[] GROUPS = {
			new Group("Pickaxes", 0xFFF0985A, "iron_pickaxe", -1500, -420, "pickaxe"),
			new Group("Axes", 0xFFE8B058, "iron_axe", -2350, 200, "axe"),
			new Group("Shovels", 0xFFD8BE80, "iron_shovel", -1600, 900, "shovel"),
			new Group("Hoes", 0xFFB8D068, "iron_hoe", -2500, 1150, "hoe"),
			new Group("Blades", 0xFFF0605A, "iron_sword", -350, -1350, "sword", "spear", "mace"),
			new Group("Archery", 0xFF6AE09A, "bow", 700, -1150, "bow", "crossbow", "trident"),
			new Group("Helmets", 0xFF6AB0F8, "iron_helmet", 1750, -1500, "helmet"),
			new Group("Chestplates", 0xFF5A9CF0, "iron_chestplate", 1800, -650, "chestplate"),
			new Group("Leggings", 0xFF6A90E8, "iron_leggings", 1850, 200, "leggings"),
			new Group("Boots", 0xFF7A88E8, "iron_boots", 1900, 1050, "boots"),
			new Group("Shields", 0xFF5AD0D8, "shield", 850, 900, "shield"),
			new Group("Wings", 0xFFC08AF8, "elytra", 0, 1500, "elytra"),
			new Group("Odd Tools", 0xFFA0E070, "fishing_rod", -850, 1500, "fishing_rod", "shears", "flint_and_steel", "brush", "on_a_stick", "other"),
	};

	static final class Node {
		ItemRecord r;
		float lx, ly; // inside the constellation
		float x, y; // on the sky
		Group group;
		Col col;
		ItemStack stack;
		float importance;
		float reveal;
		boolean match = true;
		float seed;
	}

	record Edge(Node from, Node to, String type, String label, boolean cross, float bend) {
	}

	static final class Col {
		Group group;
		int index;
		float ox, oy; // centre on the sky
		float hw, hh; // half size of its box (local, centred)
		float reveal;
		int alive, total;
		long mined, kills;
		ItemRecord oldest;
		float[] titleHit = new float[4];

		float cx() {
			return ox;
		}

		float cy() {
			return oy;
		}
	}

	private record Shooting(float x, float y, float vx, float vy, long start, float life) {
	}

	private record Spark(float x, float y, float vx, float vy, long start, int color) {
	}

	private final Screen parent;
	private final List<Node> nodes = new ArrayList<>();
	private final Map<String, Node> byId = new HashMap<>();
	private final List<Edge> edges = new ArrayList<>();
	private final List<Col> cols = new ArrayList<>();
	private int laidOutRevision = -1;
	private float minX, minY, maxX, maxY;

	private float camX, camY, zoom = 0.3f, targetZoom = 0.3f;
	private Float targetX, targetY;
	private float velX, velY;
	private double anchorMx = -1, anchorMy;
	private float anchorSx, anchorSy;
	private boolean centered;
	private long openedAt;
	private boolean introCamera = true;
	private float introFrom, introTo;
	private int tourIndex = -1;

	private String selected;
	private final DetailPanel panel = new DetailPanel(this);
	private boolean showRecords;
	private Button recordsButton;
	private int filter;
	private EditBox search;
	private final List<Button> filterButtons = new ArrayList<>();

	private boolean dragging;
	private double dragDist;
	private long lastHoverSound;
	private Object lastHover;
	private final Set<Integer> keysDown = new HashSet<>();

	private float[][] stars;
	private float[] bandX, bandY, bandA;
	private final List<Shooting> shooting = new ArrayList<>();
	private final List<Spark> sparks = new ArrayList<>();
	private long nextShooting;
	private final Random rnd = new Random();

	// minimap
	private int mmX, mmY, mmW = 168, mmH = 112;

	private float glowGain = 1f;

	private static FimConfig cfg() {
		return FimConfig.get();
	}

	/** The constellation's colour from the theme (or the player's custom colours). */
	static int color(Group g) {
		for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i] == g) return cfg().groupColor(i);
		return 0xFFFFFFFF;
	}

	private static int accent() {
		return cfg().theme.accent & 0xFFFFFF;
	}

	private void sound(net.minecraft.sounds.SoundEvent e, float pitch, float volume) {
		if (!cfg().sounds || cfg().soundVolume <= 0) return;
		minecraft.getSoundManager().play(SimpleSoundInstance.forUI(e, pitch, volume * cfg().soundVolume / 100f));
	}

	public TreeScreen(Screen parent) {
		super(Component.literal("Forged in Memory"));
		this.parent = parent;
	}

	// ================================================================ setup

	@Override
	protected void init() {
		SkyTextures.ensure();
		boolean first = openedAt == 0;
		if (first) {
			openedAt = System.currentTimeMillis();
			sound(SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7f, 0.7f);
			if (ClientArchive.serverHasMod && ClientPlayNetworking.canSend(Net.RequestPayload.TYPE)) {
				ClientPlayNetworking.send(new Net.RequestPayload());
			}
			makeStars();
		}
		filterButtons.clear();
		String[] names = {"All", "Alive", "Fallen"};
		for (int i = 0; i < 3; i++) {
			final int f = i;
			filterButtons.add(addRenderableWidget(Button.builder(Component.literal(names[i]), btn -> {
				filter = f;
				applyFilter();
				click();
			}).bounds(8 + i * 52, 8, 50, 18).build()));
		}
		recordsButton = addRenderableWidget(Button.builder(Component.literal(showRecords ? "Hide records" : "Records"), btn -> {
			toggleRecords();
		}).bounds(8 + 3 * 52 + 6, 8, 74, 18).build());
		String oldSearch = search == null ? "" : search.getValue();
		search = new EditBox(font, 8 + 3 * 52 + 6 + 78, 8, 140, 18, Component.literal("Search"));
		search.setHint(Component.literal("Search by name..."));
		search.setValue(oldSearch);
		search.setResponder(s -> applyFilter());
		addRenderableWidget(search);
		if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("yet_another_config_lib_v3")) {
			addRenderableWidget(Button.builder(Component.literal("⚙"), btn -> {
				click();
				minecraft.gui.setScreen(dev.k3ytel.forgedinmemory.client.config.FimConfigScreen.create(this));
			}).bounds(8 + 3 * 52 + 6 + 78 + 144, 8, 20, 18).tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Settings"))).build());
		}
		mmX = 10;
		mmY = height - mmH - 22;
		relayout();
	}

	private void toggleRecords() {
		showRecords = !showRecords;
		if (showRecords) selected = null;
		recordsButton.setMessage(Component.literal(showRecords ? "Hide records" : "Records"));
		click();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void click() {
		if (cfg().sounds) minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.6f));
	}

	private void makeStars() {
		Random r = new Random(42);
		int[] counts = {560, 320, 80};
		stars = new float[3][];
		for (int l = 0; l < 3; l++) {
			float[] a = new float[counts[l] * 4];
			for (int i = 0; i < counts[l]; i++) {
				a[i * 4] = r.nextFloat();
				a[i * 4 + 1] = r.nextFloat();
				a[i * 4 + 2] = r.nextFloat() * 6.28f;
				a[i * 4 + 3] = 0.4f + r.nextFloat() * 0.6f;
			}
			stars[l] = a;
		}
		int band = 460;
		bandX = new float[band];
		bandY = new float[band];
		bandA = new float[band];
		for (int i = 0; i < band; i++) {
			bandX[i] = r.nextFloat();
			bandY[i] = (float) (r.nextGaussian() * 0.06);
			bandA[i] = 0.3f + r.nextFloat() * 0.7f;
		}
	}

	// ================================================================ layout

	private static Group groupOf(String kind) {
		for (Group g : GROUPS) if (g.has(kind)) return g;
		return GROUPS[GROUPS.length - 1];
	}

	private static float hash(String id, int salt) {
		int h = (id + "#" + salt).hashCode();
		h ^= h >>> 16;
		h *= 0x45d9f3b;
		h ^= h >>> 16;
		return ((h & 0xFFFF) / 65535f);
	}

	private static float importance(ItemRecord r) {
		double score = r.totalMined() + r.totalKills() * 8.0 + r.stat("absorbed") + r.stat("blocked") * 4 + r.stat("flightDist") / 20
				+ r.stat("shots") * 2 + r.stat("fish") * 5 + r.stat("crops") + r.stat("bosses") * 2000;
		return (float) Math.min(1.3, Math.log10(1 + score) * 0.28);
	}

	private void relayout() {
		laidOutRevision = ClientArchive.revision;
		nodes.clear();
		byId.clear();
		edges.clear();
		cols.clear();
		List<ItemRecord> all = new ArrayList<>(ClientArchive.RECORDS.values());
		all.sort(Comparator.comparingLong(r -> r.bornAt));
		Map<Group, List<ItemRecord>> grouped = new LinkedHashMap<>();
		for (Group g : GROUPS) grouped.put(g, new ArrayList<>());
		for (ItemRecord r : all) grouped.get(groupOf(r.kind == null ? "other" : r.kind)).add(r);
		Map<String, Group> groupOfId = new HashMap<>();
		for (Map.Entry<Group, List<ItemRecord>> e : grouped.entrySet()) for (ItemRecord r : e.getValue()) groupOfId.put(r.id, e.getKey());

		int ci = 0;
		for (Map.Entry<Group, List<ItemRecord>> e : grouped.entrySet()) {
			List<ItemRecord> list = e.getValue();
			if (list.isEmpty()) continue;
			Group group = e.getKey();
			Col col = new Col();
			col.group = group;
			col.index = ci;
			col.ox = group.ax();
			col.oy = group.ay();

			Map<String, Integer> gen = new HashMap<>();
			int maxGen = 0;
			for (ItemRecord r : list) {
				int g = 0;
				for (ItemRecord.Link l : r.parents) {
					Integer pg = gen.get(l.from);
					if (pg != null && groupOfId.get(l.from) == group) g = Math.max(g, pg + 1);
				}
				gen.put(r.id, g);
				maxGen = Math.max(maxGen, g);
			}
			List<Node> mine = new ArrayList<>();
			int row = 0;
			for (int g = 0; g <= maxGen; g++) {
				List<ItemRecord> inGen = new ArrayList<>();
				for (ItemRecord r : list) if (gen.get(r.id) == g) inGen.add(r);
				for (int start = 0; start < inGen.size(); start += MAX_PER_ROW) {
					List<ItemRecord> chunk = inGen.subList(start, Math.min(inGen.size(), start + MAX_PER_ROW));
					float rowW = (chunk.size() - 1) * SLOT_W;
					float wave = (float) Math.sin(row * 0.85 + ci * 1.7) * 48f;
					for (int i = 0; i < chunk.size(); i++) {
						ItemRecord r = chunk.get(i);
						Node n = new Node();
						n.r = r;
						n.group = group;
						n.col = col;
						n.stack = Names.stack(r.item);
						n.importance = importance(r);
						n.seed = hash(r.id, 9);
						n.lx = -rowW / 2f + i * SLOT_W + wave + (hash(r.id, 1) - 0.5f) * 36f;
						n.ly = -row * ROW_H + (hash(r.id, 2) - 0.5f) * 30f;
						n.reveal = 0.5f + ci * 0.1f + row * 0.05f + i * 0.03f;
						mine.add(n);
						if (!r.isDead()) col.alive++;
						col.total++;
						col.mined += r.totalMined();
						col.kills += r.totalKills();
						if (col.oldest == null || r.bornAt < col.oldest.bornAt) col.oldest = r;
					}
					row++;
				}
			}
			float lminX = Float.MAX_VALUE, lmaxX = -Float.MAX_VALUE, lminY = Float.MAX_VALUE, lmaxY = -Float.MAX_VALUE;
			for (Node n : mine) {
				lminX = Math.min(lminX, n.lx);
				lmaxX = Math.max(lmaxX, n.lx);
				lminY = Math.min(lminY, n.ly);
				lmaxY = Math.max(lmaxY, n.ly);
			}
			float mx = (lminX + lmaxX) / 2f, my = (lminY + lmaxY) / 2f;
			for (Node n : mine) {
				n.lx -= mx;
				n.ly -= my;
			}
			col.hw = (lmaxX - lminX) / 2f + 120;
			col.hh = (lmaxY - lminY) / 2f + 130;
			col.reveal = 0.35f + ci * 0.1f;
			nodes.addAll(mine);
			cols.add(col);
			ci++;
		}

		relax();

		for (Node n : nodes) {
			n.x = n.col.ox + n.lx;
			n.y = n.col.oy + n.ly;
			byId.put(n.r.id, n);
		}
		minX = minY = -300;
		maxX = maxY = 300;
		for (Col c : cols) {
			minX = Math.min(minX, c.ox - c.hw);
			maxX = Math.max(maxX, c.ox + c.hw);
			minY = Math.min(minY, c.oy - c.hh);
			maxY = Math.max(maxY, c.oy + c.hh + 90);
		}

		for (Node child : nodes) {
			Map<String, Double> materialSum = new LinkedHashMap<>();
			Map<String, String> materialWhat = new HashMap<>();
			for (ItemRecord.Link l : child.r.parents) {
				Node from = byId.get(l.from);
				if (from == null || from == child) continue;
				if ("material".equals(l.type)) {
					materialSum.merge(l.from, l.amount, Double::sum);
					materialWhat.putIfAbsent(l.from, l.what);
				} else {
					edges.add(new Edge(from, child, l.type, null, from.group != child.group, bendFor(from, child)));
				}
			}
			for (Map.Entry<String, Double> m : materialSum.entrySet()) {
				Node from = byId.get(m.getKey());
				edges.add(new Edge(from, child, "material", amount(m.getValue()) + " " + materialWhat.get(m.getKey()), from.group != child.group, bendFor(from, child)));
			}
		}
		if (!centered) {
			centered = true;
			float[] fit = fitAll();
			camX = fit[0];
			camY = fit[1];
			introTo = fit[2];
			introFrom = fit[2] * 0.55f;
			zoom = targetZoom = introFrom;
			if (!cfg().intro) {
				openedAt = System.currentTimeMillis() - 30_000L;
				introCamera = false;
				zoom = targetZoom = introTo;
			}
		}
		if (selected != null && !byId.containsKey(selected)) selected = null;
		applyFilter();
	}

	/** Push overlapping constellation boxes apart (the Soul star in the middle stays put). */
	private void relax() {
		for (int it = 0; it < 120; it++) {
			boolean moved = false;
			for (int i = 0; i < cols.size(); i++) {
				Col a = cols.get(i);
				// keep clear of the Soul star at (0,0)
				float sx = a.hw + 260 - Math.abs(a.ox), sy = a.hh + 260 - Math.abs(a.oy);
				if (sx > 0 && sy > 0) {
					if (sx < sy) a.ox += Math.signum(a.ox == 0 ? 1 : a.ox) * sx;
					else a.oy += Math.signum(a.oy == 0 ? 1 : a.oy) * sy;
					moved = true;
				}
				for (int j = i + 1; j < cols.size(); j++) {
					Col b = cols.get(j);
					float ox = a.hw + b.hw + PAD - Math.abs(a.ox - b.ox);
					float oy = a.hh + b.hh + PAD - Math.abs(a.oy - b.oy);
					if (ox > 0 && oy > 0) {
						moved = true;
						if (ox < oy) {
							float d = ox / 2f * (a.ox <= b.ox ? 1 : -1);
							a.ox -= d;
							b.ox += d;
						} else {
							float d = oy / 2f * (a.oy <= b.oy ? 1 : -1);
							a.oy -= d;
							b.oy += d;
						}
					}
				}
			}
			if (!moved) break;
		}
	}

	private float[] fitAll() {
		float bw = maxX - minX + 200, bh = maxY - minY + 200;
		float z = Math.max(0.12f, Math.min(0.9f, Math.min(width / bw, (height - 60) / bh)));
		return new float[] {(minX + maxX) / 2f, (minY + maxY) / 2f, z};
	}

	private float fitZoom(Col c) {
		float z = Math.min((width - PANEL_W - 40) / (c.hw * 2 + 160), (height - 90) / (c.hh * 2 + 160));
		return Math.max(0.3f, Math.min(1.4f, z));
	}

	private static float bendFor(Node a, Node b) {
		float h = hash(a.r.id + b.r.id, 4);
		return (h < 0.5f ? -1 : 1) * (0.12f + h * 0.14f);
	}

	private static String amount(double v) {
		if (Math.abs(v - Math.round(v)) < 0.05) return String.valueOf(Math.round(v));
		return String.format(Locale.US, "%.1f", v);
	}

	private void applyFilter() {
		String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
		for (Node n : nodes) {
			boolean ok = switch (filter) {
				case 1 -> !n.r.isDead();
				case 2 -> n.r.isDead();
				default -> true;
			};
			if (ok && !q.isEmpty()) ok = n.r.name != null && n.r.name.toLowerCase(Locale.ROOT).contains(q);
			n.match = ok;
		}
		for (int i = 0; i < filterButtons.size(); i++) filterButtons.get(i).active = i != filter;
	}

	private void flyTo(float x, float y, float z) {
		targetX = x;
		targetY = y;
		targetZoom = z;
		anchorMx = -1;
		introCamera = false;
		velX = velY = 0;
	}

	public void select(String id) {
		selected = id;
		panel.reset();
		Node n = byId.get(id);
		if (n != null) {
			float z = Math.max(zoom, 0.9f);
			flyTo(n.x + (PANEL_W / 2f) / z, n.y, z);
			for (int i = 0; i < 26; i++) {
				double ang = rnd.nextDouble() * Math.PI * 2;
				float sp = 40 + rnd.nextFloat() * 120;
				sparks.add(new Spark(n.x, n.y, (float) Math.cos(ang) * sp, (float) Math.sin(ang) * sp, System.currentTimeMillis(), color(n.group)));
			}
		}
		sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f, 0.5f);
	}

	public net.minecraft.client.gui.Font font() {
		return font;
	}

	// ================================================================ coordinates + input

	private float sx(float x) {
		return (x - camX) * zoom + width / 2f;
	}

	private float sy(float y) {
		return (y - camY) * zoom + height / 2f;
	}

	private float skyX(double mx) {
		return (float) ((mx - width / 2.0) / zoom + camX);
	}

	private float skyY(double my) {
		return (float) ((my - height / 2.0) / zoom + camY);
	}

	private boolean overPanel(double mx, double my) {
		return (selected != null || showRecords) && mx >= width - PANEL_W - 8 && my >= 32 && my <= height - 20;
	}

	private boolean overMinimap(double mx, double my) {
		return cfg().minimap && mx >= mmX && mx < mmX + mmW && my >= mmY && my < mmY + mmH;
	}

	private boolean overSoul(double mx, double my) {
		return Math.hypot(sx(0) - mx, sy(0) - my) < Math.max(18, 40 * zoom);
	}

	private Col titleAt(double mx, double my) {
		for (Col c : cols) {
			float[] h = c.titleHit;
			if (mx >= h[0] && mx < h[2] && my >= h[1] && my < h[3]) return c;
		}
		return null;
	}

	private Node nodeAt(double mx, double my) {
		Node best = null;
		float bestD = 18f;
		for (Node n : nodes) {
			float d = (float) Math.hypot(sx(n.x) - mx, sy(n.y) - my);
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
		double mx = event.x(), my = event.y();
		if (overPanel(mx, my)) {
			panel.click(mx, my);
			return true;
		}
		if (overMinimap(mx, my)) {
			float[] m = minimapMap();
			flyTo(m[0] + (float) ((mx - mmX - 6) / m[2]), m[1] + (float) ((my - mmY - 14) / m[2]), zoom);
			click();
			return true;
		}
		if (my < 30) return false;
		dragging = true;
		dragDist = 0;
		velX = velY = 0;
		introCamera = false;
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			camX -= (float) (dx / zoom);
			camY -= (float) (dy / zoom);
			velX = (float) (-dx / zoom);
			velY = (float) (-dy / zoom);
			targetX = targetY = null;
			dragDist += Math.abs(dx) + Math.abs(dy);
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			dragging = false;
			if (dragDist < 4) {
				velX = velY = 0;
				double mx = event.x(), my = event.y();
				Node n = nodeAt(mx, my);
				Col title = titleAt(mx, my);
				if (n != null) {
					select(n.r.id);
				} else if (title != null) {
					selected = null;
					flyTo(title.ox, title.oy, fitZoom(title));
					click();
				} else if (overSoul(mx, my)) {
					selected = null;
					if (!showRecords) toggleRecords();
					flyTo(0, 0, Math.max(zoom, 0.6f));
				} else {
					selected = null;
				}
			}
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sxv, double syv) {
		if (overPanel(mx, my)) {
			panel.scroll(syv);
			return true;
		}
		introCamera = false;
		targetZoom = (float) Math.max(0.12, Math.min(2.6, targetZoom * Math.pow(1.18, syv)));
		anchorMx = mx;
		anchorMy = my;
		anchorSx = skyX(mx);
		anchorSy = skyY(my);
		targetX = targetY = null;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int k = event.key();
		boolean typing = search != null && search.isFocused();
		if (k == InputConstants.KEY_ESCAPE && selected != null && !typing) {
			selected = null;
			return true;
		}
		if (!typing) {
			if (k == InputConstants.KEY_F) {
				float[] f = fitAll();
				flyTo(f[0], f[1], f[2]);
				click();
				return true;
			}
			if (k == InputConstants.KEY_SPACE && !cols.isEmpty()) {
				tourIndex = (tourIndex + 1) % cols.size();
				Col c = cols.get(tourIndex);
				selected = null;
				flyTo(c.ox, c.oy, fitZoom(c));
				sound(SoundEvents.AMETHYST_BLOCK_CHIME, 0.8f + tourIndex * 0.05f, 0.4f);
				return true;
			}
			if (k == InputConstants.KEY_EQUALS) {
				targetZoom = Math.min(2.6f, targetZoom * 1.25f);
				anchorMx = -1;
				return true;
			}
			if (k == InputConstants.KEY_MINUS) {
				targetZoom = Math.max(0.12f, targetZoom / 1.25f);
				anchorMx = -1;
				return true;
			}
			if (isPanKey(k)) {
				keysDown.add(k);
				introCamera = false;
				targetX = targetY = null;
				return true;
			}
		}
		return super.keyPressed(event);
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		keysDown.remove(event.key());
		return super.keyReleased(event);
	}

	private static boolean isPanKey(int k) {
		return k == InputConstants.KEY_W || k == InputConstants.KEY_A || k == InputConstants.KEY_S || k == InputConstants.KEY_D
				|| k == InputConstants.KEY_UP || k == InputConstants.KEY_DOWN || k == InputConstants.KEY_LEFT || k == InputConstants.KEY_RIGHT;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
	}

	// ================================================================ frame

	private static float clamp01(float v) {
		return Math.max(0, Math.min(1, v));
	}

	private static float easeOutBack(float t) {
		float c1 = 1.70158f, c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	private static float easeOutCubic(float t) {
		return 1 - (float) Math.pow(1 - t, 3);
	}

	private static int argb(int rgb, float a) {
		return (Math.max(0, Math.min(255, Math.round(a * 255))) << 24) | (rgb & 0xFFFFFF);
	}

	private void updateCamera(float elapsed) {
		if (!keysDown.isEmpty()) {
			float sp = 9f / zoom;
			if (keysDown.contains(InputConstants.KEY_A) || keysDown.contains(InputConstants.KEY_LEFT)) camX -= sp;
			if (keysDown.contains(InputConstants.KEY_D) || keysDown.contains(InputConstants.KEY_RIGHT)) camX += sp;
			if (keysDown.contains(InputConstants.KEY_W) || keysDown.contains(InputConstants.KEY_UP)) camY -= sp;
			if (keysDown.contains(InputConstants.KEY_S) || keysDown.contains(InputConstants.KEY_DOWN)) camY += sp;
		}
		if (introCamera) {
			float t = easeOutCubic(clamp01((elapsed - 0.1f) / 1.8f));
			zoom = introFrom + (introTo - introFrom) * t;
			targetZoom = zoom;
			if (t >= 1) introCamera = false;
			return;
		}
		if (Math.abs(targetZoom - zoom) > 0.0005f) {
			zoom += (targetZoom - zoom) * 0.16f;
			if (anchorMx >= 0) {
				camX = anchorSx - (float) ((anchorMx - width / 2.0) / zoom);
				camY = anchorSy - (float) ((anchorMy - height / 2.0) / zoom);
			}
		} else {
			anchorMx = -1;
		}
		if (targetX != null) {
			camX += (targetX - camX) * 0.12f;
			camY += (targetY - camY) * 0.12f;
			if (Math.abs(targetX - camX) < 0.5f && Math.abs(targetY - camY) < 0.5f) targetX = targetY = null;
		} else if (!dragging && (Math.abs(velX) > 0.01f || Math.abs(velY) > 0.01f)) {
			camX += velX;
			camY += velY;
			velX *= 0.9f;
			velY *= 0.9f;
		}
		// keep the sky in view
		camX = Math.max(minX - 400, Math.min(maxX + 400, camX));
		camY = Math.max(minY - 400, Math.min(maxY + 400, camY));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		if (laidOutRevision != ClientArchive.revision) relayout();
		long nowMs = System.currentTimeMillis();
		float elapsed = (nowMs - openedAt) / 1000f;
		float time = (nowMs % 3_600_000L) / 1000f;
		updateCamera(elapsed);

		drawSky(g, time, nowMs);

		boolean blocked = overPanel(mouseX, mouseY) || mouseY < 30 || dragging || overMinimap(mouseX, mouseY);
		Node hover = blocked ? null : nodeAt(mouseX, mouseY);
		Col hoverCol = (blocked || hover != null) ? null : titleAt(mouseX, mouseY);
		boolean hoverSoul = !blocked && hover == null && hoverCol == null && overSoul(mouseX, mouseY);
		Object hoverKey = hover != null ? hover.r.id : hoverCol != null ? hoverCol : hoverSoul ? "soul" : null;
		if (hoverKey != null && !hoverKey.equals(lastHover) && nowMs - lastHoverSound > 90) {
			sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.3f + rnd.nextFloat() * 0.6f, 0.22f);
			lastHoverSound = nowMs;
		}
		lastHover = hoverKey;

		Node focus = hover != null ? hover : (selected != null ? byId.get(selected) : null);
		Set<Node> related = new HashSet<>();
		if (focus != null) {
			related.add(focus);
			for (Edge e : edges) {
				if (e.from == focus) related.add(e.to);
				if (e.to == focus) related.add(e.from);
			}
		}

		for (Col c : cols) drawConstellationArt(g, c, elapsed, hoverCol == c);
		drawSoulSpokes(g, time, elapsed);
		for (Edge e : edges) {
			if (e.cross) {
				FimConfig.CrossLinks mode = cfg().crossLinks;
				if (mode == FimConfig.CrossLinks.NEVER) continue;
				if (mode == FimConfig.CrossLinks.ON_FOCUS && (focus == null || (e.from != focus && e.to != focus))) continue;
			}
			drawEdge(g, e, time, elapsed, focus);
		}
		glowGain = cfg().starBrightness;
		for (Node n : nodes) drawNode(g, n, n == hover, n.r.id.equals(selected), time, elapsed, focus, related, nowMs);
		drawSoul(g, time, elapsed, hoverSoul);
		if (cfg().showExtras()) drawSparks(g, nowMs);
		glowGain = 1f;
		for (Col c : cols) drawConstellationTitle(g, c, elapsed, hoverCol == c);
		if (focus != null) {
			for (Edge e : edges) if (e.label != null && (e.from == focus || e.to == focus)) drawEdgeLabel(g, e);
		}

		drawVignette(g);
		if (nodes.isEmpty()) {
			String msg = ClientArchive.serverHasMod
					? "No items yet. Craft or pick up a tool and its star appears here."
					: "This server doesn't run Forged in Memory, so there is nothing to show yet.";
			g.centeredText(font, msg, width / 2, height / 2 + 60, 0xFFCFD8F0);
		}
		g.fillGradient(0, 0, width, 44, 0xD0000008, 0x00000008);
		if (width >= 560) spacedText(g, "FORGED IN MEMORY", width - 12, 13, 1f, (0xFF000000 | accent()), true, 1.5f);
		g.centeredText(font, "Drag · Scroll · Click a star · Space: tour · F: whole sky", width / 2, height - 12, 0x80B8C4E0);
		drawMinimap(g, mouseX, mouseY);

		super.extractRenderState(g, mouseX, mouseY, partialTick);

		if (selected != null) {
			ItemRecord r = ClientArchive.get(selected);
			if (r != null) panel.draw(g, r, width - PANEL_W - 8, 34, PANEL_W, height - 34 - 20, mouseX, mouseY);
		} else if (showRecords) {
			panel.drawRecords(g, width - PANEL_W - 8, 34, PANEL_W, height - 34 - 20, mouseX, mouseY);
		}
		if (hover != null) drawTooltip(g, hover, mouseX, mouseY);
		else if (hoverCol != null) drawColTooltip(g, hoverCol, mouseX, mouseY);
		else if (hoverSoul) drawSoulTooltip(g, mouseX, mouseY);

		float fade = 1 - clamp01(elapsed / 1.0f);
		if (fade > 0.01f) g.fill(0, 0, width, height, argb(0x000000, fade));
	}

	// ================================================================ drawing helpers

	private void blitTinted(GuiGraphicsExtractor g, Identifier tex, int texSize, float cx, float cy, float size, int argb) {
		int s = Math.max(1, Math.round(size));
		int x = Math.round(cx - s / 2f), y = Math.round(cy - s / 2f);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0f, 0f, s, s, texSize, texSize, texSize, texSize, argb);
	}

	private void glow(GuiGraphicsExtractor g, float cx, float cy, float size, int argb) {
		if (glowGain != 1f) {
			int a = Math.min(255, Math.round((argb >>> 24) * glowGain));
			argb = (a << 24) | (argb & 0xFFFFFF);
		}
		if ((argb >>> 24) < 2 || size < 1) return;
		blitTinted(g, SkyTextures.GLOW, SkyTextures.GLOW_SIZE, cx, cy, size, argb);
	}

	private void rotated(GuiGraphicsExtractor g, Identifier tex, int texSize, float cx, float cy, float size, float angle, int argb) {
		if ((argb >>> 24) < 2) return;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(cx, cy);
		pose.rotate(angle);
		blitTinted(g, tex, texSize, 0, 0, size, argb);
		pose.popMatrix();
	}

	private void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int thick, int color) {
		float dx = x2 - x1, dy = y2 - y1;
		float len = (float) Math.hypot(dx, dy);
		if (len < 0.5f || (color >>> 24) < 3) return;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x1, y1);
		pose.rotate((float) Math.atan2(dy, dx));
		int half = thick / 2;
		g.fill(0, -half, Math.max(1, Math.round(len)), thick - half, color);
		pose.popMatrix();
	}

	private static float wrap(float v, float m) {
		v %= m;
		return v < 0 ? v + m : v;
	}

	// ================================================================ sky

	private void drawSky(GuiGraphicsExtractor g, float time, long nowMs) {
		g.fillGradient(0, 0, width, height, 0xFF02030A, 0xFF08051A);
		float big = Math.max(width, height);

		float n1 = big * 2.0f;
		float p1x = -camX * 0.012f + (float) Math.sin(time * 0.03) * 20, p1y = -camY * 0.012f;
		g.blit(RenderPipelines.GUI_TEXTURED, SkyTextures.NEBULA, Math.round(width / 2f - n1 / 2 + p1x), Math.round(height / 2f - n1 / 2 + p1y),
				0f, 0f, Math.round(n1), Math.round(n1), SkyTextures.NEBULA_SIZE, SkyTextures.NEBULA_SIZE, SkyTextures.NEBULA_SIZE, SkyTextures.NEBULA_SIZE, argb(cfg().theme.nebulaTint, Math.min(1f, cfg().nebula)));
		float n2 = big * 1.6f;
		float p2x = (float) (-camX * 0.035f + Math.sin(time * 0.05) * 60), p2y = -camY * 0.035f;
		if (cfg().showExtras() && cfg().nebula > 0.02f) g.blit(RenderPipelines.GUI_TEXTURED, SkyTextures.NEBULA2, Math.round(width / 2f - n2 / 2 + p2x), Math.round(height / 2f - n2 / 2 + p2y),
				0f, 0f, Math.round(n2), Math.round(n2), SkyTextures.NEBULA_SIZE, SkyTextures.NEBULA_SIZE, SkyTextures.NEBULA_SIZE, SkyTextures.NEBULA_SIZE, argb(cfg().theme.nebulaTint, Math.min(1f, 0.69f * cfg().nebula)));
		g.fill(0, 0, width, height, 0x38000006);

		if (cfg().showAurora()) drawAurora(g, time);

		// milky way band
		if (cfg().showMilkyWay()) drawMilkyWay(g, time, big);
		drawStarLayers(g, time);
		if (cfg().showShootingStars()) drawShooting(g, nowMs);
		if (cfg().showDust()) drawDust(g, time);
	}

	private void drawMilkyWay(GuiGraphicsExtractor g, float time, float big) {
		float bx0 = -0.1f * width, by0 = 0.95f * height, bx1 = 1.1f * width, by1 = 0.05f * height;
		float shiftX = -camX * 0.02f, shiftY = -camY * 0.02f;
		for (int i = 0; i <= 22; i++) {
			float t = i / 22f;
			float x = bx0 + (bx1 - bx0) * t + shiftX, y = by0 + (by1 - by0) * t + shiftY;
			glow(g, wrap(x, width * 1.2f) - width * 0.1f, y, big * 0.34f, argb(0xB8C8FF, 0.05f));
		}
		float nx = -(by1 - by0), ny = bx1 - bx0;
		float nl = (float) Math.hypot(nx, ny);
		nx /= nl;
		ny /= nl;
		for (int i = 0; i < bandX.length; i++) {
			float t = bandX[i];
			float x = bx0 + (bx1 - bx0) * t + nx * bandY[i] * height + shiftX;
			float y = by0 + (by1 - by0) * t + ny * bandY[i] * height + shiftY;
			x = wrap(x, width);
			float tw = 0.5f + 0.5f * (float) Math.sin(time * 1.3 + i);
			g.fill(Math.round(x), Math.round(y), Math.round(x) + 1, Math.round(y) + 1, argb(0xF0F4FF, bandA[i] * (0.35f + 0.4f * tw)));
		}

	}

	private void drawStarLayers(GuiGraphicsExtractor g, float time) {
		float[] par = {0.03f, 0.08f, 0.16f};
		float tile = 1100f;
		for (int l = 0; l < 3; l++) {
			float[] a = stars[l];
			for (int i = 0; i < a.length / 4; i++) {
				float x = wrap(a[i * 4] * tile - camX * par[l], tile);
				float y = wrap(a[i * 4 + 1] * tile - camY * par[l], tile);
				float tw = 0.4f + 0.6f * (float) Math.abs(Math.sin(time * (0.6 + l * 0.4) + a[i * 4 + 2]));
				for (float ox = x; ox < width; ox += tile) {
					for (float oy = y; oy < height; oy += tile) {
						if (l == 2) {
							float s = 5 + a[i * 4 + 3] * 7;
							blitTinted(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, ox, oy, s * (0.7f + 0.3f * tw), argb(0xEEF2FF, 0.55f * tw));
						} else {
							int c = argb(l == 0 ? 0xAAB8E0 : 0xE8EEFF, a[i * 4 + 3] * tw * (l == 0 ? 0.5f : 0.85f));
							g.fill(Math.round(ox), Math.round(oy), Math.round(ox) + 1, Math.round(oy) + 1, c);
						}
					}
				}
			}
		}

	}

	private void drawShooting(GuiGraphicsExtractor g, long nowMs) {
		if (nowMs > nextShooting) {
			nextShooting = nowMs + 3000 + rnd.nextInt(5500);
			float sx0 = rnd.nextFloat() * width, sy0 = rnd.nextFloat() * height * 0.5f;
			float ang = (float) (Math.PI * (0.15 + rnd.nextFloat() * 0.25));
			float sp = 650 + rnd.nextFloat() * 450;
			shooting.add(new Shooting(sx0, sy0, (float) Math.cos(ang) * sp * (rnd.nextBoolean() ? 1 : -1), (float) Math.sin(ang) * sp, nowMs, 0.7f + rnd.nextFloat() * 0.4f));
		}
		shooting.removeIf(s -> (nowMs - s.start) / 1000f > s.life);
		for (Shooting s : shooting) {
			float t = (nowMs - s.start) / 1000f;
			float a = (float) Math.sin(Math.PI * t / s.life);
			float hx = s.x + s.vx * t, hy = s.y + s.vy * t;
			for (int k = 0; k < 12; k++) {
				float f = k / 12f;
				float x1 = hx - s.vx * 0.13f * f, y1 = hy - s.vy * 0.13f * f;
				float x2 = hx - s.vx * 0.13f * (f + 1 / 12f), y2 = hy - s.vy * 0.13f * (f + 1 / 12f);
				line(g, x1, y1, x2, y2, k < 3 ? 2 : 1, argb(0xFFFFFF, a * (1 - f)));
			}
			glow(g, hx, hy, 18, argb(0xFFFFFF, a * 0.9f));
		}

	}

	private void drawDust(GuiGraphicsExtractor g, float time) {
		for (int i = 0; i < 30; i++) {
			float bx = hash("dust", i) * width * 1.3f, by = hash("dust", i + 100) * height * 1.3f;
			float x = wrap(bx - camX * 0.4f * zoom + (float) Math.sin(time * 0.2 + i) * 30, width * 1.3f) - width * 0.15f;
			float y = wrap(by - camY * 0.4f * zoom + time * (4 + i % 5), height * 1.3f) - height * 0.15f;
			glow(g, x, y, 10 + (i % 4) * 4, argb(0xD8E0FF, 0.10f));
		}
	}

	private void drawAurora(GuiGraphicsExtractor g, float time) {
		int step = 3;
		float off = camX * 0.05f;
		for (int x = 0; x < width; x += step) {
			float fx = (x + off) * 0.006f;
			float hgt = 70 + 55 * (float) Math.sin(fx * 1.7 + time * 0.25) + 30 * (float) Math.sin(fx * 4.3 - time * 0.4);
			float a = 0.085f * (0.4f + 0.6f * (float) Math.pow(Math.max(0, Math.sin(fx * 2.3 - time * 0.33)), 2));
			float hue = 0.5f + 0.5f * (float) Math.sin(fx * 0.9 + time * 0.08);
			int r = (int) (60 + 120 * hue), gg = (int) (230 - 90 * hue), b = (int) (170 + 80 * hue);
			int col = (r << 16) | (gg << 8) | b;
			int top = 26 + (int) (10 * Math.sin(fx * 3 + time * 0.2));
			g.fillGradient(x, top, x + step, top + (int) hgt, argb(col, a), argb(col, 0));
		}
	}

	private void drawVignette(GuiGraphicsExtractor g) {
		int edge = Math.max(60, width / 9);
		g.fillGradient(0, 0, width, edge, 0x90000004, 0x00000004);
		g.fillGradient(0, height - edge, width, height, 0x00000004, 0xB0000004);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(0, height);
		pose.rotate((float) (-Math.PI / 2));
		g.fillGradient(0, 0, height, edge, 0x90000004, 0x00000004);
		pose.popMatrix();
		pose.pushMatrix();
		pose.translate(width, 0);
		pose.rotate((float) (Math.PI / 2));
		g.fillGradient(0, 0, height, edge, 0x90000004, 0x00000004);
		pose.popMatrix();
	}

	// ================================================================ soul star

	private void drawSoul(GuiGraphicsExtractor g, float time, float elapsed, boolean hover) {
		float rev = easeOutBack(clamp01((elapsed - 0.25f) / 0.6f));
		if (rev <= 0) return;
		float px = sx(0), py = sy(0);
		float zs = Math.max(0.6f, Math.min(1.6f, zoom * 1.3f)) * rev * (hover ? 1.15f : 1f);
		float beat = 0.9f + 0.1f * (float) Math.sin(time * 1.6);
		glow(g, px, py, 420 * zs, argb(0xFFF0D0, 0.07f));
		glow(g, px, py, 200 * zs * beat, argb(0xFFE2B0, 0.22f));
		glow(g, px, py, 90 * zs, argb(0xFFFFFF, 0.55f));
		rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, 120 * zs, time * 0.2f, argb(accent(), 0.55f));
		rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, 150 * zs, -time * 0.12f, argb(accent(), 0.30f));
		rotated(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, px, py, 110 * zs * beat, time * 0.07f, argb(0xFFFFFF, 1f));
		rotated(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, px, py, 80 * zs, time * 0.07f + (float) (Math.PI / 4), argb(0xFFE2B0, 0.9f));
		for (int k = 0; k < 6; k++) {
			double ang = time * 0.5 + k * Math.PI / 3;
			float ox = (float) Math.cos(ang) * 60 * zs, oy = (float) Math.sin(ang) * 24 * zs;
			glow(g, px + ox, py + oy, 10 * zs, argb(0xFFF0D0, 0.8f));
		}
		String name = minecraft.player != null ? minecraft.player.getName().getString() : "You";
		float ts = Math.max(1f, Math.min(2f, zoom * 2f));
		String label = name.toUpperCase(Locale.ROOT);
		float w = spacedWidth(label, ts, 2f);
		spacedText(g, label, px - w / 2f, py + 70 * zs, ts, argb(accent(), rev), false, 2f);
	}

	private void drawSoulSpokes(GuiGraphicsExtractor g, float time, float elapsed) {
		for (Col c : cols) {
			float rev = clamp01((elapsed - c.reveal) / 0.8f);
			if (rev <= 0) continue;
			float x1 = sx(0), y1 = sy(0), x2 = sx(c.ox), y2 = sy(c.oy + c.hh - 40);
			int segs = 26;
			float[] prev = bezier(x1, y1, x2, y2, 0.08f, 0);
			for (int i = 1; i <= Math.round(segs * rev); i++) {
				float[] p = bezier(x1, y1, x2, y2, 0.08f, i / (float) segs);
				if (i % 2 == 0) line(g, prev[0], prev[1], p[0], p[1], 1, argb(color(c.group), 0.16f));
				prev = p;
			}
			if (rev >= 1) {
				float t = (time * 0.12f + c.index * 0.137f) % 1f;
				float[] p = bezier(x1, y1, x2, y2, 0.08f, t);
				float a = (float) Math.sin(Math.PI * t);
				glow(g, p[0], p[1], 22 * Math.max(0.6f, zoom), argb(color(c.group), 0.5f * a));
			}
		}
	}

	// ================================================================ constellations

	private void drawConstellationArt(GuiGraphicsExtractor g, Col c, float elapsed, boolean hover) {
		float rev = clamp01((elapsed - c.reveal + 0.2f) / 1.0f);
		if (rev <= 0) return;
		float size = (Math.max(c.hw, c.hh) * 2 + 180) * zoom;
		float cx = sx(c.ox), cy = sy(c.oy);
		if (cx + size < 0 || cx - size > width || cy + size < 0 || cy - size > height) return;
		glow(g, cx, cy, size * 1.35f, argb(color(c.group), (hover ? 0.16f : 0.10f) * rev));
		glow(g, cx, cy, size * 0.65f, argb(color(c.group), 0.08f * rev));
		Identifier art = SkyTextures.art(c.group.art());
		if (art != null) {
			float zoomFade = 0.32f + (1 - clamp01(zoom)) * 0.3f + (hover ? 0.2f : 0);
			float breathe = 0.92f + 0.08f * (float) Math.sin(elapsed * 0.7f + c.index);
			float strength = zoomFade * rev * breathe * cfg().artStrength;
			if (strength > 0.01f) blitTinted(g, art, SkyTextures.ART_SIZE, cx, cy, size, argb(color(c.group), Math.min(1f, strength)));
		}
	}

	private void drawConstellationTitle(GuiGraphicsExtractor g, Col c, float elapsed, boolean hover) {
		float rev = clamp01((elapsed - c.reveal) / 0.8f);
		if (rev <= 0) return;
		float cx = sx(c.ox), y = sy(c.oy + c.hh - 40) + 20 * Math.max(0.6f, zoom);
		if (cx < -400 || cx > width + 400 || y < -100 || y > height + 100) return;
		float scale = Math.max(1.0f, Math.min(2.4f, 2.2f * zoom)) * (hover ? 1.08f : 1f);
		String title = c.group.title().toUpperCase(Locale.ROOT);
		String count = "  " + c.alive + "/" + c.total;
		float tw = spacedWidth(title, scale, 1.5f) + font.width(count) * scale;
		glow(g, cx, y + 4 * scale, tw * 1.5f, argb(color(c.group), (hover ? 0.32f : 0.18f) * rev));
		float x0 = cx - tw / 2f;
		spacedText(g, title, x0, y, scale, argb(hover ? 0xFFFFFF : 0xEDE6D2, rev), false, 1.5f);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x0 + spacedWidth(title, scale, 1.5f), y);
		pose.scale(scale);
		g.text(font, count, 0, 0, argb(0xFFFFFF, rev), true);
		pose.popMatrix();
		float uy = y + 11 * scale;
		float half = tw * 0.62f;
		for (int i = 0; i < 24; i++) {
			float f0 = i / 24f, f1 = (i + 1) / 24f;
			float a = (float) Math.sin(Math.PI * (f0 + f1) / 2) * 0.6f * rev;
			line(g, cx - half + 2 * half * f0, uy, cx - half + 2 * half * f1, uy, 1, argb(accent(), a));
		}
		g.centeredText(font, "◆", Math.round(cx - half - 4), Math.round(uy - 4), argb(accent(), 0.8f * rev));
		g.centeredText(font, "◆", Math.round(cx + half + 4), Math.round(uy - 4), argb(accent(), 0.8f * rev));
		c.titleHit[0] = x0 - 10;
		c.titleHit[1] = y - 4;
		c.titleHit[2] = x0 + tw + 10;
		c.titleHit[3] = uy + 6;
	}

	private float spacedWidth(String s, float scale, float spacing) {
		float w = 0;
		for (int i = 0; i < s.length(); i++) w += (font.width(String.valueOf(s.charAt(i))) + spacing) * scale;
		return w - spacing * scale;
	}

	private void spacedText(GuiGraphicsExtractor g, String s, float x, float y, float scale, int color, boolean right, float spacing) {
		float w = spacedWidth(s, scale, spacing);
		float cx = right ? x - w : x;
		Matrix3x2fStack pose = g.pose();
		for (int i = 0; i < s.length(); i++) {
			String ch = String.valueOf(s.charAt(i));
			pose.pushMatrix();
			pose.translate(cx, y);
			pose.scale(scale);
			g.text(font, ch, 0, 0, color, true);
			pose.popMatrix();
			cx += (font.width(ch) + spacing) * scale;
		}
	}

	// ================================================================ edges

	private static float[] bezier(float x1, float y1, float x2, float y2, float bend, float t) {
		float mx = (x1 + x2) / 2f, my = (y1 + y2) / 2f;
		float dx = x2 - x1, dy = y2 - y1;
		float cx = mx - dy * bend, cy = my + dx * bend;
		float u = 1 - t;
		return new float[] {u * u * x1 + 2 * u * t * cx + t * t * x2, u * u * y1 + 2 * u * t * cy + t * t * y2};
	}

	private static int edgeColor(String type) {
		return switch (type) {
			case "material" -> 0xF2CF84;
			case "successor" -> 0xDCE8FF;
			case "anvil", "merge" -> 0xC890FF;
			default -> 0x9098A8;
		};
	}

	private void drawEdge(GuiGraphicsExtractor g, Edge e, float time, float elapsed, Node focus) {
		float start = Math.max(e.from.reveal, e.to.reveal) + 0.15f;
		float frac = clamp01((elapsed - start) / 0.7f);
		if (frac <= 0) return;
		boolean focused = focus != null && (e.from == focus || e.to == focus);
		float a = 1f;
		if (!(e.from.match && e.to.match)) a *= 0.2f;
		if (focus != null && !focused) a *= 0.22f;
		if (e.cross && !focused) a *= 0.35f;
		int rgb = edgeColor(e.type);
		float x1 = sx(e.from.x), y1 = sy(e.from.y), x2 = sx(e.to.x), y2 = sy(e.to.y);
		if (Math.max(x1, x2) < -80 || Math.min(x1, x2) > width + 80 || Math.max(y1, y2) < -80 || Math.min(y1, y2) > height + 80) return;
		boolean dashed = "successor".equals(e.type) || e.cross;
		int segs = e.cross ? 30 : 18;
		int drawn = Math.round(segs * frac);
		float[] prev = bezier(x1, y1, x2, y2, e.bend, 0);
		for (int i = 1; i <= drawn; i++) {
			float[] p = bezier(x1, y1, x2, y2, e.bend, i / (float) segs);
			if (!dashed || i % 2 == 0) {
				line(g, prev[0], prev[1], p[0], p[1], 5, argb(rgb, 0.07f * a));
				line(g, prev[0], prev[1], p[0], p[1], 3, argb(rgb, (focused ? 0.28f : 0.16f) * a));
				line(g, prev[0], prev[1], p[0], p[1], 1, argb(rgb, (focused ? 1f : 0.8f) * a));
			}
			prev = p;
		}
		if (frac >= 1 && a > 0.3f) {
			float speed = focused ? 0.55f : 0.32f;
			for (int k = 0; k < (focused ? 2 : 1); k++) {
				float t = (time * speed + hash(e.from.r.id + e.to.r.id, 7) + k * 0.5f) % 1f;
				float[] p = bezier(x1, y1, x2, y2, e.bend, t);
				float pulse = (float) Math.sin(Math.PI * t);
				glow(g, p[0], p[1], 16 * Math.max(0.7f, zoom), argb(rgb, 0.7f * pulse * a));
				glow(g, p[0], p[1], 5, argb(0xFFFFFF, 0.9f * pulse * a));
			}
		}
	}

	private void drawEdgeLabel(GuiGraphicsExtractor g, Edge e) {
		float[] p = bezier(sx(e.from.x), sy(e.from.y), sx(e.to.x), sy(e.to.y), e.bend, 0.5f);
		int w = font.width(e.label);
		int x = Math.round(p[0] - w / 2f), y = Math.round(p[1] - 5);
		g.fill(x - 3, y - 2, x + w + 3, y + 10, 0xC0080A14);
		g.outline(x - 3, y - 2, w + 6, 12, 0x80F2CF84);
		g.text(font, e.label, x, y, 0xFFF2CF84, false);
	}

	// ================================================================ stars

	static int statusColor(ItemRecord r) {
		if (r.isDead()) return 0xFF9A9AAA;
		if (r.isMissing()) return 0xFFFFB347;
		return 0xFFFFF0C8;
	}

	static String statusText(ItemRecord r) {
		if (r.isDead()) return "Fallen";
		if (r.isMissing()) return "Missing";
		return r.bestEffort ? "Alive (best effort)" : "Alive";
	}

	private void drawNode(GuiGraphicsExtractor g, Node n, boolean hover, boolean sel, float time, float elapsed, Node focus, Set<Node> related, long nowMs) {
		float px = sx(n.x), py = sy(n.y);
		if (px < -150 || px > width + 150 || py < -150 || py > height + 150) return;
		float pop = clamp01((elapsed - n.reveal) / 0.45f);
		if (pop <= 0) return;
		float popScale = easeOutBack(pop);
		ItemRecord r = n.r;
		float a = pop;
		if (!n.match) a *= 0.22f;
		if (focus != null && !related.contains(n)) a *= 0.35f;
		float zs = Math.max(0.5f, Math.min(1.5f, zoom));
		float s = (1 + n.importance) * zs * popScale * (hover ? 1.25f : 1f);
		int col = color(n.group);
		float twinkle = 0.85f + 0.15f * (float) Math.sin(time * 2.1f + n.seed * 20f);

		if (sel) {
			int pw = Math.max(2, Math.round(7 * zs));
			g.fillGradient(Math.round(px - pw), 0, Math.round(px + pw), Math.round(py), argb(col, 0f), argb(col, 0.24f * a));
			g.fillGradient(Math.round(px - 1), 0, Math.round(px + 1), Math.round(py), argb(0xFFFFFF, 0f), argb(0xFFFFFF, 0.55f * a));
			rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, 66 * s, time * 0.6f, argb(accent(), 0.8f * a));
			rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, 88 * s, -time * 0.35f, argb(col, 0.5f * a));
		}

		if (r.isDead()) {
			glow(g, px, py, 46 * s, argb(0x8A8FA0, 0.30f * a));
			rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, 26 * s, n.seed * 6f, argb(0x7A6A78, 0.45f * a));
			blitTinted(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, px, py, 18 * s, argb(0xA8ACBA, 0.55f * a));
			if (r.death != null && nowMs - r.death.at < 15 * 60_000L) {
				for (int k = 0; k < 2; k++) {
					float ph = (time / 2.4f + k * 0.5f) % 1f;
					rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, (20 + 110 * ph) * s, 0, argb(0xFF7A4A, (1 - ph) * 0.6f * a));
				}
				glow(g, px, py, 70 * s, argb(0xFF6A3A, 0.25f * a));
			}
			// slowly falling ash
			for (int k = 0; k < (cfg().showExtras() ? 3 : 0); k++) {
				float ph = (time * 0.25f + k / 3f + n.seed) % 1f;
				glow(g, px + (float) Math.sin(ph * 6 + k) * 8 * s, py + ph * 26 * s, 4 * s, argb(0xA0A0B0, (1 - ph) * 0.5f * a));
			}
		} else if (r.isMissing()) {
			float flick = 0.5f + 0.5f * (float) Math.abs(Math.sin(time * 4 + n.seed * 9));
			glow(g, px, py, 70 * s, argb(0xFFB347, 0.35f * flick * a));
			rotated(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, px, py, 34 * s, time * 0.2f, argb(0xFFD08A, 0.8f * flick * a));
		} else {
			glow(g, px, py, 120 * s * twinkle, argb(col, 0.22f * a));
			glow(g, px, py, 54 * s, argb(col, 0.60f * a));
			rotated(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, px, py, 46 * s * twinkle, time * 0.12f + n.seed * 6f, argb(0xFFFFFF, 0.95f * a));
			if (n.importance > 0.85f) {
				rotated(g, SkyTextures.SPARKLE, SkyTextures.SPARKLE_SIZE, px, py, 34 * s, time * 0.12f + n.seed * 6f + (float) (Math.PI / 4), argb(col, 0.9f * a));
				glow(g, px, py, 190 * s, argb(col, 0.10f * a));
				for (int k = 0; k < (cfg().showExtras() ? 3 : 0); k++) {
					double ang = time * (0.9 + n.seed * 0.4) + k * Math.PI * 2 / 3;
					float ox = (float) Math.cos(ang) * 30 * s, oy = (float) Math.sin(ang) * 13 * s;
					glow(g, px + ox, py + oy, 9 * s, argb(0xFFFFFF, 0.85f * a));
					glow(g, px + ox, py + oy, 20 * s, argb(col, 0.4f * a));
				}
			}
			glow(g, px, py, 12 * s, argb(0xFFFFFF, a));
		}
		if (r.customName != null) {
			rotated(g, SkyTextures.RING, SkyTextures.RING_SIZE, px, py, 42 * s, -time * 0.8f + n.seed * 3, argb(0xF2CF84, 0.85f * a));
		}

		boolean showIcon = (zoom >= 0.8f && !r.isDead()) || hover || sel;
		if (showIcon && !n.stack.isEmpty()) {
			float is = Math.min(1.5f, zs) * popScale;
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate(px, py);
			pose.scale(is);
			g.fakeItem(n.stack, -8, -8);
			pose.popMatrix();
		}

		float labelFade = switch (cfg().labels) {
			case ALWAYS -> 1f;
			case HOVER -> (hover || sel) ? 1f : 0f;
			default -> (hover || sel) ? 1f : clamp01((zoom - 0.55f) / 0.25f);
		};
		if (labelFade > 0.03f) {
			String label = (r.isDead() ? "☠ " : r.isMissing() ? "? " : "") + r.name;
			float ls = Math.max(0.7f, Math.min(1.15f, zoom * 0.9f));
			Matrix3x2fStack pose = g.pose();
			pose.pushMatrix();
			pose.translate(px, py + 20 * zs + 4);
			pose.scale(ls);
			int w = font.width(label);
			g.text(font, label, -w / 2, 0, argb(statusColor(r), labelFade * a), true);
			if (zoom >= 1.1f || sel) {
				String t = Titles.of(r);
				int tw = font.width(t);
				g.text(font, t, -tw / 2, 10, argb(0xB9A2FF, labelFade * a * 0.85f), true);
			}
			pose.popMatrix();
		}
	}

	private void drawSparks(GuiGraphicsExtractor g, long nowMs) {
		sparks.removeIf(s -> nowMs - s.start > 900);
		for (Spark s : sparks) {
			float t = (nowMs - s.start) / 1000f;
			float ease = 1 - (float) Math.pow(1 - Math.min(1, t / 0.9f), 2);
			float x = sx(s.x + s.vx * ease), y = sy(s.y + s.vy * ease);
			float a = 1 - t / 0.9f;
			glow(g, x, y, 14 * Math.max(0.6f, zoom), argb(s.color, a * 0.9f));
			glow(g, x, y, 4, argb(0xFFFFFF, a));
		}
	}

	// ================================================================ minimap

	/** {originX, originY, scale} mapping sky → minimap pixels. */
	private float[] minimapMap() {
		float bw = maxX - minX, bh = maxY - minY;
		float sc = Math.min((mmW - 12) / bw, (mmH - 20) / bh);
		float ox = (minX + maxX) / 2f - (mmW - 12) / 2f / sc;
		float oy = (minY + maxY) / 2f - (mmH - 20) / 2f / sc;
		return new float[] {ox, oy, sc};
	}

	private void drawMinimap(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		if (cols.isEmpty() || !cfg().minimap) return;
		boolean hov = overMinimap(mouseX, mouseY);
		g.fillGradient(mmX, mmY, mmX + mmW, mmY + mmH, hov ? 0xE0101422 : 0xC00A0C16, hov ? 0xE008090F : 0xC006070C);
		g.outline(mmX, mmY, mmW, mmH, 0xFF6C5A3A);
		ornaments(g, mmX, mmY, mmW, mmH, (0xFF000000 | accent()));
		g.text(font, "STAR CHART", mmX + 6, mmY + 4, (0xFF000000 | accent()), false);
		float[] m = minimapMap();
		g.enableScissor(mmX + 1, mmY + 13, mmX + mmW - 1, mmY + mmH - 1);
		for (Col c : cols) {
			float cx = mmX + 6 + (c.ox - m[0]) * m[2], cy = mmY + 14 + (c.oy - m[1]) * m[2];
			glow(g, cx, cy, Math.max(c.hw, c.hh) * 2 * m[2] * 1.4f, argb(color(c.group), 0.25f));
		}
		for (Node n : nodes) {
			int x = Math.round(mmX + 6 + (n.x - m[0]) * m[2]), y = Math.round(mmY + 14 + (n.y - m[1]) * m[2]);
			int c = n.r.isDead() ? 0xFF6A6A78 : (0xFF000000 | color(n.group));
			g.fill(x, y, x + 1, y + 1, c);
			if (n.r.id.equals(selected)) g.outline(x - 2, y - 2, 5, 5, 0xFFFFFFFF);
		}
		int sx0 = Math.round(mmX + 6 + (0 - m[0]) * m[2]), sy0 = Math.round(mmY + 14 + (0 - m[1]) * m[2]);
		g.fill(sx0 - 1, sy0 - 1, sx0 + 2, sy0 + 2, 0xFFFFF0D0);
		float vx0 = mmX + 6 + (skyX(0) - m[0]) * m[2], vy0 = mmY + 14 + (skyY(0) - m[1]) * m[2];
		float vx1 = mmX + 6 + (skyX(width) - m[0]) * m[2], vy1 = mmY + 14 + (skyY(height) - m[1]) * m[2];
		g.outline(Math.round(vx0), Math.round(vy0), Math.max(2, Math.round(vx1 - vx0)), Math.max(2, Math.round(vy1 - vy0)), 0xC0FFFFFF);
		g.disableScissor();
	}

	// ================================================================ tooltips

	private void glassBox(GuiGraphicsExtractor g, List<String> text, List<Integer> colors, int mx, int my, int glowColor) {
		int w = 0;
		for (String l : text) w = Math.max(w, font.width(l));
		w = Math.min(w, 230);
		int h = text.size() * 11 + 6;
		int x = mx + 14, y = my - h / 2;
		if (x + w + 12 > width) x = mx - w - 22;
		y = Math.max(34, Math.min(height - h - 20, y));
		glow(g, x + w / 2f + 4, y + h / 2f, Math.max(w, h) * 1.6f, argb(glowColor, 0.18f));
		g.fillGradient(x, y, x + w + 10, y + h, 0xEE0C0F1C, 0xEE06070E);
		g.outline(x, y, w + 10, h, 0xFF6C5A3A);
		ornaments(g, x, y, w + 10, h, (0xFF000000 | accent()));
		int ly = y + 4;
		for (int i = 0; i < text.size(); i++) {
			g.text(font, font.plainSubstrByWidth(text.get(i), w), x + 5, ly, colors.get(i), false);
			ly += 11;
		}
	}

	private void drawTooltip(GuiGraphicsExtractor g, Node n, int mx, int my) {
		ItemRecord r = n.r;
		List<String> t = new ArrayList<>();
		List<Integer> c = new ArrayList<>();
		t.add(r.name);
		c.add((0xFF000000 | accent()));
		t.add(Titles.of(r));
		c.add(0xFFB9A2FF);
		t.add(statusText(r) + " · " + Names.age(r));
		c.add(statusColor(r));
		t.add(Obituary.headline(r));
		c.add(0xFFB8C2D8);
		if (r.isDead()) {
			t.add(Obituary.causeLine(r));
			c.add(0xFF9A9AAA);
		}
		glassBox(g, t, c, mx, my, color(n.group));
	}

	private void drawColTooltip(GuiGraphicsExtractor g, Col col, int mx, int my) {
		List<String> t = new ArrayList<>();
		List<Integer> c = new ArrayList<>();
		t.add("The " + col.group.title());
		c.add((0xFF000000 | accent()));
		t.add(col.total + " remembered · " + col.alive + " alive · " + (col.total - col.alive) + " fallen");
		c.add(0xFFB8C2D8);
		if (col.mined > 0) {
			t.add(Names.num(col.mined) + " blocks mined");
			c.add(0xFFB8C2D8);
		}
		if (col.kills > 0) {
			t.add(Names.num(col.kills) + " kills");
			c.add(0xFFB8C2D8);
		}
		if (col.oldest != null) {
			t.add("First of its line: " + col.oldest.name);
			c.add(0xFF9AA4BA);
		}
		t.add("Click to fly there");
		c.add(0xFF8C96AA);
		glassBox(g, t, c, mx, my, color(col.group));
	}

	private void drawSoulTooltip(GuiGraphicsExtractor g, int mx, int my) {
		long alive = 0, mined = 0, kills = 0;
		for (Node n : nodes) {
			if (!n.r.isDead()) alive++;
			mined += n.r.totalMined();
			kills += n.r.totalKills();
		}
		String name = minecraft.player != null ? minecraft.player.getName().getString() : "You";
		List<String> t = List.of(name + "'s armory", nodes.size() + " items remembered · " + alive + " still with you",
				Names.num(mined) + " blocks · " + Names.num(kills) + " kills", "Click for the Hall of Records");
		List<Integer> c = List.of((0xFF000000 | accent()), 0xFFB8C2D8, 0xFFB8C2D8, 0xFF8C96AA);
		glassBox(g, t, c, mx, my, 0xFFE2B0);
	}

	/** Little gold L-shaped corners. */
	static void ornaments(GuiGraphicsExtractor g, int x, int y, int w, int h, int c) {
		int L = 5;
		g.fill(x - 1, y - 1, x + L, y, c);
		g.fill(x - 1, y - 1, x, y + L, c);
		g.fill(x + w - L, y - 1, x + w + 1, y, c);
		g.fill(x + w, y - 1, x + w + 1, y + L, c);
		g.fill(x - 1, y + h, x + L, y + h + 1, c);
		g.fill(x - 1, y + h - L, x, y + h + 1, c);
		g.fill(x + w - L, y + h, x + w + 1, y + h + 1, c);
		g.fill(x + w, y + h - L, x + w + 1, y + h + 1, c);
	}
}
