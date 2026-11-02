package dev.k3ytel.forgedinmemory.client.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

import org.joml.Matrix3x2fStack;

import dev.k3ytel.forgedinmemory.client.ClientArchive;
import dev.k3ytel.forgedinmemory.client.Names;
import dev.k3ytel.forgedinmemory.client.Obituary;
import dev.k3ytel.forgedinmemory.client.Story;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import dev.k3ytel.forgedinmemory.client.Titles;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** The card on the right side of the tree: everything about one item in tabs, or the Hall of Records. */
final class DetailPanel {
	private static final int GOLD = 0xFFE8D7A8;
	private static final int TEXT = 0xFFC8D0E0;
	private static final int DIM = 0xFF8C96AA;
	private static final int ORANGE = 0xFFE8945A;
	private static final int RED = 0xFFE0584A;
	private static final int BLUE = 0xFF5A9BE0;
	private static final int GREEN = 0xFF6AD08A;
	private static final int TEAL = 0xFF4FC0C8;
	private static final int PURPLE = 0xFFB07AE8;
	private static final int YELLOW = 0xFFF0CF84;

	private final TreeScreen screen;
	private int tab;
	private double scroll;
	private final List<int[]> tabHits = new ArrayList<>();
	private final List<String> tabNames = new ArrayList<>();
	private final List<Object[]> linkHits = new ArrayList<>();
	private String lastId;

	private sealed interface Row permits Head, Text, Bar, Link, Gap, Pair {
	}

	private record Head(String text) implements Row {
	}

	private record Text(String text, int color) implements Row {
	}

	/** label ...... value, on one line */
	private record Pair(String label, String value) implements Row {
	}

	private record Bar(String label, double value, double max, String valueText, int color) implements Row {
	}

	private record Link(String text, String id) implements Row {
	}

	private record Gap() implements Row {
	}

	DetailPanel(TreeScreen screen) {
		this.screen = screen;
	}

	void reset() {
		scroll = 0;
	}

	void scroll(double amount) {
		scroll = Math.max(0, scroll - amount * 18);
	}

	void click(double mx, double my) {
		for (int i = 0; i < tabHits.size(); i++) {
			int[] h = tabHits.get(i);
			if (mx >= h[0] && mx < h[2] && my >= h[1] && my < h[3]) {
				tab = i;
				scroll = 0;
				return;
			}
		}
		for (Object[] l : linkHits) {
			int[] h = (int[]) l[0];
			if (mx >= h[0] && mx < h[2] && my >= h[1] && my < h[3]) {
				String id = (String) l[1];
				if (id.startsWith("copy:")) {
					Minecraft mc = Minecraft.getInstance();
					mc.keyboardHandler.setClipboard(id.substring(5));
					SystemToast.addOrUpdate(mc.gui.toastManager(), new SystemToast.SystemToastId(2000L), Component.literal("Copied"), Component.literal(id.substring(5)));
				} else {
					screen.select(id);
				}
				return;
			}
		}
	}

	private void frame(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fillGradient(x, y, x + w, y + h, 0xEE0C0F1C, 0xEE06070E);
		g.fillGradient(x + 1, y + 1, x + w - 1, y + 40, 0x30E8D7A8, 0x00E8D7A8);
		g.outline(x, y, w, h, 0xFF6C5A3A);
		g.outline(x + 2, y + 2, w - 4, h - 4, 0x306C5A3A);
		TreeScreen.ornaments(g, x, y, w, h, 0xFFE8D7A8);
		g.centeredText(screen.font(), "◆", x + w / 2, y - 4, 0xFFE8D7A8);
	}

	// ---------------------------------------------------------------- item card

	void draw(GuiGraphicsExtractor g, ItemRecord r, int x, int y, int w, int h, int mouseX, int mouseY) {
		Font font = screen.font();
		if (!r.id.equals(lastId)) {
			lastId = r.id;
			tab = 0;
			scroll = 0;
		}
		frame(g, x, y, w, h);

		ItemStack s = Names.stack(r.item);
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + 8, y + 8);
		pose.scale(2f);
		if (!s.isEmpty()) g.fakeItem(s, 0, 0);
		pose.popMatrix();
		if (r.isDead()) g.fill(x + 8, y + 8, x + 40, y + 40, 0x88050510);
		int tx = x + 46;
		List<FormattedCharSequence> nameLines = font.split(Component.literal(r.name), w - 52);
		int ny = y + 7;
		for (int i = 0; i < Math.min(2, nameLines.size()); i++) {
			g.text(font, nameLines.get(i), tx, ny, GOLD, true);
			ny += 10;
		}
		g.text(font, font.plainSubstrByWidth(Titles.of(r), w - 52), tx, ny, 0xFFB9A2FF, false);
		ny += 10;
		g.text(font, TreeScreen.statusText(r) + " · " + Names.age(r), tx, ny, TreeScreen.statusColor(r), false);
		int cy = Math.max(y + 46, ny + 12);

		tabNames.clear();
		if (r.isDead()) tabNames.add("Obituary");
		tabNames.add("Story");
		tabNames.add("Life");
		tabNames.add("Work");
		tabNames.add("Fights");
		tabNames.add("World");
		tabNames.add("Family");
		if (tab >= tabNames.size()) tab = 0;
		cy = drawTabs(g, x, w, cy, mouseX, mouseY);

		List<Row> rows = switch (tabNames.get(tab)) {
			case "Obituary" -> obituary(r);
			case "Story" -> story(r);
			case "Work" -> work(r);
			case "Fights" -> fights(r);
			case "World" -> world(r);
			case "Family" -> family(r);
			default -> life(r);
		};
		drawRows(g, rows, x, cy, w, y + h - 6, mouseX, mouseY);
	}

	private int drawTabs(GuiGraphicsExtractor g, int x, int w, int cy, int mouseX, int mouseY) {
		Font font = screen.font();
		tabHits.clear();
		int tabX = x + 6;
		for (int i = 0; i < tabNames.size(); i++) {
			String t = tabNames.get(i);
			int tw = font.width(t) + 6;
			if (tabX + tw > x + w - 6) {
				tabX = x + 6;
				cy += 15;
			}
			boolean on = i == tab;
			boolean hov = mouseX >= tabX && mouseX < tabX + tw && mouseY >= cy && mouseY < cy + 13;
			g.fill(tabX, cy, tabX + tw, cy + 13, on ? 0x80E8945A : hov ? 0x40FFFFFF : 0x20FFFFFF);
			g.text(font, t, tabX + 3, cy + 3, on ? 0xFFFFFFFF : TEXT, false);
			tabHits.add(new int[] {tabX, cy, tabX + tw, cy + 13});
			tabX += tw + 2;
		}
		return cy + 18;
	}

	private void drawRows(GuiGraphicsExtractor g, List<Row> rows, int x, int top, int w, int bottom, int mouseX, int mouseY) {
		Font font = screen.font();
		g.enableScissor(x + 1, top, x + w - 1, bottom);
		int ry = top - (int) scroll;
		linkHits.clear();
		int inner = w - 16;
		for (Row row : rows) {
			switch (row) {
				case Head hd -> {
					ry += 3;
					g.text(font, hd.text.toUpperCase(Locale.ROOT), x + 8, ry, GOLD, false);
					g.fill(x + 8, ry + 10, x + w - 8, ry + 11, 0x40E8D7A8);
					ry += 15;
				}
				case Text t -> {
					for (FormattedCharSequence l : font.split(Component.literal(t.text), inner)) {
						g.text(font, l, x + 8, ry, t.color, false);
						ry += 10;
					}
				}
				case Pair p -> {
					int vw = font.width(p.value);
					String label = font.plainSubstrByWidth(p.label, inner - vw - 6);
					g.text(font, label, x + 8, ry, TEXT, false);
					int lw = font.width(label);
					for (int dx = x + 10 + lw; dx < x + w - 10 - vw; dx += 3) g.fill(dx, ry + 7, dx + 1, ry + 8, 0x30FFFFFF);
					g.text(font, p.value, x + w - 8 - vw, ry, 0xFFFFFFFF, false);
					ry += 10;
				}
				case Bar b -> {
					int lw = 92;
					String label = font.plainSubstrByWidth(b.label, lw - 4);
					g.text(font, label, x + 8, ry, TEXT, false);
					int bx = x + 8 + lw, bw = inner - lw - 34;
					double f = b.max <= 0 ? 0 : Math.min(1, b.value / b.max);
					g.fill(bx, ry + 1, bx + bw, ry + 8, 0x30FFFFFF);
					g.fill(bx, ry + 1, bx + (int) Math.round(bw * f), ry + 8, b.color);
					g.text(font, b.valueText, bx + bw + 4, ry, DIM, false);
					ry += 11;
				}
				case Link l -> {
					boolean hov = mouseX >= x + 8 && mouseX < x + w - 8 && mouseY >= ry && mouseY < ry + 10 && mouseY >= top && mouseY < bottom;
					String txt = font.plainSubstrByWidth("› " + l.text, inner);
					g.text(font, txt, x + 8, ry, hov ? 0xFFFFFFFF : YELLOW, false);
					linkHits.add(new Object[] {new int[] {x + 8, Math.max(ry, top), x + w - 8, Math.min(ry + 10, bottom)}, l.id});
					ry += 11;
				}
				case Gap gp -> ry += 6;
			}
		}
		int contentH = ry + (int) scroll - top;
		double maxScroll = Math.max(0, contentH - (bottom - top));
		if (scroll > maxScroll) scroll = maxScroll;
		g.disableScissor();
		if (maxScroll > 0) {
			int trackH = bottom - top;
			int thumbH = Math.max(16, (int) (trackH * (trackH / (double) contentH)));
			int thumbY = top + (int) ((trackH - thumbH) * (scroll / maxScroll));
			g.fill(x + w - 4, top, x + w - 2, bottom, 0x30FFFFFF);
			g.fill(x + w - 4, thumbY, x + w - 2, thumbY + thumbH, 0xA0E8D7A8);
		}
	}

	// ---------------------------------------------------------------- helpers

	private static String n(double v) {
		return Names.num(v);
	}

	private static String hearts(double damage) {
		double h = damage / 2;
		return h < 10 ? String.format(Locale.US, "%.1f", h) : Names.num(h);
	}

	private static void pair(List<Row> rows, ItemRecord r, String key, String label) {
		if (r.stat(key) > 0) rows.add(new Pair(label, n(r.stat(key))));
	}

	private static void time(List<Row> rows, ItemRecord r, String key, String label) {
		if (r.stat(key) > 0) rows.add(new Pair(label, Names.duration(r.stat(key))));
	}

	private void bars(List<Row> rows, Map<String, ? extends Number> m, int limit, int color, java.util.function.Function<String, String> name, java.util.function.Function<Double, String> fmt) {
		List<Map.Entry<String, ? extends Number>> l = new ArrayList<>(m.entrySet());
		l.sort((a, b) -> Double.compare(b.getValue().doubleValue(), a.getValue().doubleValue()));
		double max = l.isEmpty() ? 1 : l.get(0).getValue().doubleValue();
		for (int i = 0; i < Math.min(limit, l.size()); i++) {
			double v = l.get(i).getValue().doubleValue();
			if (v <= 0) continue;
			rows.add(new Bar(name.apply(l.get(i).getKey()), v, max, fmt.apply(v), color));
		}
		if (l.size() > limit) {
			double rest = 0;
			for (int i = limit; i < l.size(); i++) rest += l.get(i).getValue().doubleValue();
			rows.add(new Text("+ " + (l.size() - limit) + " more (" + fmt.apply(rest) + ")", DIM));
		}
	}

	private static String biome(String id) {
		if (id == null) return "?";
		int c = id.indexOf(':');
		String p = (c >= 0 ? id.substring(c + 1) : id).replace('_', ' ');
		return p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1);
	}

	private static String cap(String s) {
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	// ---------------------------------------------------------------- tabs

	private List<Row> obituary(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		ItemRecord.Death d = r.death;
		rows.add(new Head("Here lies " + r.name));
		rows.add(new Text(Titles.full(r), 0xFFB9A2FF));
		rows.add(new Text("Day " + r.bornDay + " – Day " + (d != null ? d.day : "?") + "  (" + Names.age(r) + ")", DIM));
		rows.add(new Gap());
		rows.add(new Text(Obituary.causeLine(r), TEXT));
		if (d != null && d.pos != null) {
			rows.add(new Gap());
			rows.add(new Head("Where it fell"));
			rows.add(new Link("⌖ " + Story.place(d) + "  (click to copy)", "copy:" + d.pos[0] + " " + d.pos[1] + " " + d.pos[2]));
			double fb = Story.distanceFromBirth(r);
			if (fb >= 0) rows.add(new Pair("Distance from its birthplace", n(fb) + " blocks"));
			if (d.ownerName != null) rows.add(new Pair("Owner", d.ownerName));
			if (!d.witnesses.isEmpty()) rows.add(new Text("Witnessed by " + String.join(", ", d.witnesses), DIM));
			long realMs = d.at - r.bornAt;
			rows.add(new Pair("Real time together", Names.duration(realMs / 1000.0)));
		}
		rows.add(new Gap());
		rows.add(new Text("“" + Obituary.epitaph(r) + "”", GOLD));
		if (!r.lastMoments.isEmpty()) {
			rows.add(new Head("Final moments"));
			for (int i = 0; i < r.lastMoments.size(); i++) {
				boolean lastOne = i == r.lastMoments.size() - 1;
				rows.add(new Text((lastOne ? "† " : "· ") + Story.moment(r.lastMoments.get(i)), lastOne ? TEXT : DIM));
			}
		}
		rows.add(new Gap());
		rows.add(new Head("Remembered for"));
		for (String s : achievements(r)) rows.add(new Text("• " + s, TEXT));
		if (r.lastBlock != null) rows.add(new Text("The last block it broke: " + Names.block(r.lastBlock), DIM));
		if (r.lastKill != null) rows.add(new Text("Its last kill: " + Names.article(Names.entity(r.lastKill)), DIM));
		return rows;
	}

	private static List<String> achievements(ItemRecord r) {
		List<String> out = new ArrayList<>();
		if (r.totalMined() > 0) out.add(n(r.totalMined()) + " blocks mined");
		if (r.stat("diamonds") > 0) out.add(n(r.stat("diamonds")) + " diamonds found");
		if (r.totalKills() > 0) out.add(n(r.totalKills()) + " kills");
		if (r.stat("bosses") > 0) out.add(n(r.stat("bosses")) + " bosses slain");
		if (r.stat("absorbed") > 0) out.add(hearts(r.stat("absorbed")) + " hearts of damage absorbed");
		if (r.stat("blocked") > 0) out.add(n(r.stat("blocked")) + " attacks blocked");
		if (r.stat("flightDist") > 0) out.add(n(r.stat("flightDist")) + " blocks flown");
		if (r.stat("fish") > 0) out.add(n(r.stat("fish")) + " fish caught");
		if (r.stat("distance") > 0) out.add(n(r.stat("distance")) + " blocks travelled together");
		if (r.stat("deathsWorn") + r.stat("deathsHeld") > 0) out.add("Stayed with you through " + n(r.stat("deathsWorn") + r.stat("deathsHeld")) + " deaths");
		if (out.isEmpty()) out.add("Being there");
		return out;
	}

	private List<Row> story(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Head("Its sign"));
		rows.add(new Text("✦ " + Story.sign(r), 0xFFB9A2FF));
		rows.add(new Text(Story.signTrait(r), DIM));
		String bm = Story.birthMoment(r);
		if (bm != null) rows.add(new Text(bm, TEXT));
		List<String> traits = Story.traits(r);
		if (!traits.isEmpty()) {
			rows.add(new Head("Personality"));
			rows.add(new Text(String.join(" · ", traits), YELLOW));
		}
		List<String> ranks = Story.ranks(r, ClientArchive.RECORDS.values());
		if (!ranks.isEmpty()) {
			rows.add(new Head("Rankings"));
			for (String s : ranks) rows.add(new Text(s, GOLD));
		}
		rows.add(new Head("Favourites"));
		String fb = Obituary.top(r.mined), fk = Obituary.top(r.kills), fbi = Obituary.top(r.minedBiome);
		if (fb != null) rows.add(new Pair("Favourite block", Names.block(fb)));
		if (fk != null) rows.add(new Pair("Favourite prey", Names.entity(fk)));
		if (fbi != null) rows.add(new Pair("Favourite place to dig", Story.biome(fbi)));
		String dimTop = null;
		double dv = 0;
		for (Map.Entry<String, Double> e : r.timeIn.entrySet()) {
			if (e.getValue() > dv) {
				dv = e.getValue();
				dimTop = e.getKey();
			}
		}
		if (dimTop != null) rows.add(new Pair("Home dimension", Names.dim(dimTop)));
		String nem = Story.nemesis(r);
		if (nem != null) rows.add(new Text("Nemesis: " + nem, 0xFFE0A0A0));
		if (r.stat("savedLife") > 0) rows.add(new Pair("Times it saved your life", n(r.stat("savedLife"))));
		if (r.stat("drops") > 0) rows.add(new Pair("Times you dropped it", n(r.stat("drops"))));
		if (!r.killsPerDay.isEmpty()) {
			long best = 0, bestDay = 0;
			for (Map.Entry<Long, Long> e : r.killsPerDay.entrySet()) if (e.getValue() > best) {
				best = e.getValue();
				bestDay = e.getKey();
			}
			rows.add(new Pair("Bloodiest day", "day " + bestDay + " (" + n(best) + " kills)"));
		}
		rows.add(new Head("Fun facts"));
		for (String f : Story.facts(r, ClientArchive.RECORDS.values())) rows.add(new Text("• " + f, TEXT));
		if (!r.lastMoments.isEmpty() && !r.isDead()) {
			rows.add(new Head("Lately"));
			for (int i = r.lastMoments.size() - 1; i >= 0; i--) rows.add(new Text("· " + Story.moment(r.lastMoments.get(i)), DIM));
		}
		return rows;
	}

	private List<Row> life(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		rows.add(new Head("Birth"));
		rows.add(new Text(Obituary.born(r), TEXT));
		if (r.bornPos != null) rows.add(new Text("At " + r.bornPos[0] + ", " + r.bornPos[1] + ", " + r.bornPos[2] + (r.bornBiome != null && !r.bornBiome.isEmpty() ? " (" + biome(r.bornBiome) + ")" : ""), DIM));
		if (r.bornWorld != null) rows.add(new Text("World: " + r.bornWorld, DIM));
		if (!r.isDead() && r.lastSeenPos != null) {
			rows.add(new Head("Last seen"));
			rows.add(new Link("⌖ " + r.lastSeenPos[0] + ", " + r.lastSeenPos[1] + ", " + r.lastSeenPos[2] + " in " + Names.dim(r.lastSeenDim) + "  (click to copy)",
					"copy:" + r.lastSeenPos[0] + " " + r.lastSeenPos[1] + " " + r.lastSeenPos[2]));
		}

		rows.add(new Head("Its life"));
		if (r.maxDamage > 0 && !r.isDead()) {
			int left = r.maxDamage - r.lastDamage;
			rows.add(new Bar("Durability", left, r.maxDamage, left + "/" + r.maxDamage, GREEN));
		}
		time(rows, r, "heldSec", "Time in hand");
		time(rows, r, "wornSec", "Time worn");
		pair(rows, r, "durabilityUsed", "Durability spent");
		if (r.maxDamage > 0 && r.stat("durabilityUsed") > 0) rows.add(new Pair("Lifetimes of durability", String.format(Locale.US, "%.1f×", r.stat("durabilityUsed") / r.maxDamage)));
		pair(rows, r, "repairsAnvil", "Anvil repairs");
		pair(rows, r, "mendingRepaired", "Durability from Mending");
		pair(rows, r, "anvilUses", "Times on an anvil");
		pair(rows, r, "tableEnchants", "Enchanting table uses");
		pair(rows, r, "levelsSpent", "XP levels spent on it");
		if (r.stat("closestCall") > 0) rows.add(new Pair("Closest call", hearts(Math.max(1, r.stat("closestCall"))) + " hearts left"));
		pair(rows, r, "deathsWorn", "Your deaths while worn");
		pair(rows, r, "deathsHeld", "Your deaths while held");
		if (!r.enchants.isEmpty()) {
			rows.add(new Head("Enchantments"));
			for (Map.Entry<String, Integer> e : r.enchants.entrySet()) rows.add(new Text(Names.enchant(e.getKey(), e.getValue()), 0xFFB9A2FF));
		}
		if (!r.names.isEmpty()) {
			rows.add(new Head("Names"));
			rows.add(new Text(String.join(" → ", r.names), TEXT));
		}
		if (r.ownerName != null) rows.add(new Text("Owner: " + r.ownerName + (r.owners.size() > 1 ? " (" + r.owners.size() + " owners so far)" : ""), DIM));
		rows.add(new Head("Timeline"));
		int from = Math.max(0, r.events.size() - 30);
		for (int i = r.events.size() - 1; i >= from; i--) {
			ItemRecord.LifeEvent e = r.events.get(i);
			String t = eventText(e);
			if (t != null) rows.add(new Text("Day " + e.day + ": " + t, i == r.events.size() - 1 ? TEXT : DIM));
		}
		return rows;
	}

	private static String eventText(ItemRecord.LifeEvent e) {
		return switch (e.type) {
			case "born" -> "Born";
			case "renamed" -> "Renamed “" + e.a + "”";
			case "enchanted" -> "Enchanted with " + Names.enchant(e.a, parseInt(e.b));
			case "disenchanted" -> "Lost " + Names.enchant(e.a, 1);
			case "upgraded" -> "Upgraded into " + Names.itemName(e.b);
			case "owner" -> "Now owned by " + e.a;
			case "recovered" -> "Recovered by " + e.a;
			case "revived" -> "Came back from the dead";
			case "dropped" -> "Dropped";
			case "missing" -> "Went missing when its owner died";
			case "died" -> "Died";
			case "repaired" -> "Repaired at an anvil";
			case "absorbed" -> "Absorbed " + (e.b != null ? e.b : "another item") + " in an anvil";
			case "boss" -> "Slew " + Names.article(Names.entity(e.a)) + "!";
			case "dimension" -> "First trip to " + Names.dim(e.a);
			case "death_witness" -> "Was there when its owner died";
			case "milestone" -> ("mined".equals(e.a) ? "Mined its " + Names.num(parseInt(e.b)) + "th block" : "Claimed its " + Names.num(parseInt(e.b)) + "th kill");
			case "saved" -> "Saved your life (hit by " + Story.who(e.a) + ")";
			default -> null;
		};
	}

	private static int parseInt(String s) {
		try {
			return Integer.parseInt(s);
		} catch (Exception e) {
			return 1;
		}
	}

	private List<Row> work(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		long total = r.totalMined();
		if (total > 0) {
			rows.add(new Head("Mined " + n(total) + " blocks"));
			bars(rows, r.mined, 10, ORANGE, Names::block, Names::num);
			rows.add(new Gap());
			pair(rows, r, "ores", "Ores");
			pair(rows, r, "diamonds", "Diamonds");
			pair(rows, r, "debris", "Ancient debris");
			pair(rows, r, "spawners", "Spawners broken");
			pair(rows, r, "logs", "Logs chopped");
			pair(rows, r, "crops", "Crops harvested");
			pair(rows, r, "darkMined", "Mined in pitch darkness");
			if (r.stats.containsKey("deepestY")) rows.add(new Pair("Deepest dig", "Y " + n(r.stat("deepestY"))));
			if (r.stats.containsKey("highestY")) rows.add(new Pair("Highest dig", "Y " + n(r.stat("highestY"))));
			pair(rows, r, "longestDig", "Longest dig without a break");
			pair(rows, r, "bestMinute", "Most blocks in one minute");
			if (r.firstBlock != null) rows.add(new Pair("First block", Names.block(r.firstBlock)));
			if (r.lastBlock != null) rows.add(new Pair("Latest block", Names.block(r.lastBlock)));
			if (!r.minedPerDay.isEmpty()) {
				rows.add(new Head("Blocks per day"));
				List<Map.Entry<Long, Long>> days = new ArrayList<>(r.minedPerDay.entrySet());
				long max = 0, bestDay = 0;
				for (Map.Entry<Long, Long> e : days) {
					if (e.getValue() > max) {
						max = e.getValue();
						bestDay = e.getKey();
					}
				}
				for (int i = Math.max(0, days.size() - 12); i < days.size(); i++) {
					Map.Entry<Long, Long> e = days.get(i);
					rows.add(new Bar("Day " + e.getKey(), e.getValue(), max, n(e.getValue()), e.getKey() == bestDay ? YELLOW : BLUE));
				}
				rows.add(new Text("Best day: day " + bestDay + " (" + n(max) + " blocks), " + days.size() + " working days", DIM));
			}
		}
		boolean other = false;
		for (String k : new String[] {"tilled", "sheared", "fires", "brushed", "boosts", "casts"}) if (r.stat(k) > 0) other = true;
		if (other) {
			rows.add(new Head("Other work"));
			pair(rows, r, "tilled", "Blocks tilled");
			pair(rows, r, "sheared", "Things sheared");
			pair(rows, r, "fires", "Fires lit");
			pair(rows, r, "brushed", "Brushings");
			pair(rows, r, "boosts", "Boosts");
			pair(rows, r, "casts", "Casts");
		}
		if (!r.caught.isEmpty()) {
			rows.add(new Head("Caught"));
			pair(rows, r, "fish", "Fish");
			pair(rows, r, "treasure", "Treasure");
			pair(rows, r, "junk", "Junk");
			bars(rows, r.caught, 8, TEAL, Names::itemName, Names::num);
		}
		if (r.stat("flightDist") > 0) {
			rows.add(new Head("Flight"));
			rows.add(new Pair("Distance flown", n(r.stat("flightDist")) + " blocks"));
			time(rows, r, "flightSec", "Time in the air");
			time(rows, r, "longestFlight", "Longest flight");
			rows.add(new Pair("Top speed", n(r.stat("topSpeed")) + " blocks/s"));
			if (r.stats.containsKey("highestFlight")) rows.add(new Pair("Highest altitude", "Y " + n(r.stat("highestFlight"))));
			pair(rows, r, "rockets", "Rockets burned");
			pair(rows, r, "crashes", "Wall crashes");
		}
		if (rows.isEmpty()) rows.add(new Text("No work recorded yet.", DIM));
		return rows;
	}

	private static final String[][] KILL_KINDS = {
			{"onehit", "One-hit kills"}, {"midair", "Mid-air kills"}, {"flying", "Kills while flying"}, {"night", "Kills at night"},
			{"clutch", "Clutch kills (you under 3 hearts)"}, {"revenge", "Revenge kills"}, {"onfire", "Kills while on fire"},
			{"underwater", "Underwater kills"}, {"baby", "Babies (shame)"}};

	private List<Row> fights(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		long kills = r.totalKills();
		if (kills > 0) {
			rows.add(new Head(n(kills) + (kills == 1 ? " kill" : " kills")));
			bars(rows, r.kills, 10, RED, Names::entity, Names::num);
			rows.add(new Gap());
			Map<String, Long> cats = new LinkedHashMap<>();
			for (String k : new String[] {"hostile", "animal", "player"}) if (r.killKinds.getOrDefault(k, 0L) > 0) cats.put(k, r.killKinds.get(k));
			if (!cats.isEmpty()) bars(rows, cats, 3, 0xFFC04848, k -> switch (k) {
				case "hostile" -> "Monsters";
				case "animal" -> "Animals";
				default -> "Players";
			}, Names::num);
			for (String[] kk : KILL_KINDS) {
				long v = r.killKinds.getOrDefault(kk[0], 0L);
				if (v > 0) rows.add(new Pair(kk[1], n(v)));
			}
			pair(rows, r, "bestStreak", "Longest kill streak");
			pair(rows, r, "bosses", "Bosses slain");
			if (r.stat("longestKill") > 0) rows.add(new Pair("Farthest kill", n(r.stat("longestKill")) + " blocks"));
			if (r.firstKill != null) rows.add(new Pair("First kill", Names.entity(r.firstKill) + (r.firstKillDay >= 0 ? " (day " + r.firstKillDay + ")" : "")));
			if (r.lastKill != null) rows.add(new Pair("Latest kill", Names.entity(r.lastKill)));
			if (!r.namedVictims.isEmpty()) rows.add(new Text("Named victims: " + String.join(", ", r.namedVictims), 0xFFE0A0A0));
		}
		if (r.stat("damageDealt") > 0) {
			rows.add(new Head("Damage"));
			rows.add(new Pair("Dealt", hearts(r.stat("damageDealt")) + " hearts"));
			rows.add(new Pair("Biggest hit", hearts(r.stat("biggestHit")) + " hearts"));
			if (r.stat("hitsLanded") > 0) {
				rows.add(new Pair("Hits landed", n(r.stat("hitsLanded"))));
				rows.add(new Pair("Average hit", hearts(r.stat("damageDealt") / r.stat("hitsLanded")) + " hearts"));
			}
			pair(rows, r, "crits", "Critical hits");
		}
		if (r.stat("shots") > 0 || r.stat("throws") > 0) {
			rows.add(new Head("Aim"));
			if (r.stat("shots") > 0) {
				double acc = r.stat("hits") / Math.max(1, r.stat("shots"));
				rows.add(new Bar("Accuracy", acc, 1, Math.round(acc * 100) + "%", GREEN));
				rows.add(new Pair("Shots / hits", n(r.stat("shots")) + " / " + n(r.stat("hits"))));
			}
			if (r.stat("throws") > 0) rows.add(new Pair("Throws / hits", n(r.stat("throws")) + " / " + n(r.stat("tridentHits"))));
			if (r.stat("longestShot") > 0) rows.add(new Pair("Longest hit", n(r.stat("longestShot")) + " blocks"));
		}
		if (r.stat("hitsTaken") > 0) {
			rows.add(new Head("Defence"));
			rows.add(new Pair("Hits taken", n(r.stat("hitsTaken"))));
			rows.add(new Pair("Damage absorbed", hearts(r.stat("absorbed")) + " hearts"));
			if (r.stat("worstHit") > 0) rows.add(new Pair("Worst hit taken", hearts(r.stat("worstHit")) + " hearts"));
			pair(rows, r, "fallsTaken", "Falls broken");
			pair(rows, r, "explosionsSurvived", "Explosions survived");
			if (!r.absorbedBy.isEmpty()) bars(rows, r.absorbedBy, 8, BLUE, DetailPanel::cap, v -> hearts(v) + "♥");
		}
		if (r.stat("blocked") > 0) {
			rows.add(new Head("Shield work"));
			rows.add(new Pair("Attacks blocked", n(r.stat("blocked"))));
			pair(rows, r, "projectilesBlocked", "Arrows and projectiles");
			pair(rows, r, "meleeBlocked", "Melee hits");
			pair(rows, r, "explosionsBlocked", "Explosions");
			rows.add(new Pair("Damage blocked", hearts(r.stat("blockedDamage")) + " hearts"));
			if (r.stat("biggestBlocked") > 0) rows.add(new Pair("Biggest hit blocked", hearts(r.stat("biggestBlocked")) + " hearts"));
		}
		if (rows.isEmpty()) rows.add(new Text("It has never fought.", DIM));
		return rows;
	}

	private List<Row> world(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		if (!r.timeIn.isEmpty()) {
			rows.add(new Head("Time per dimension"));
			bars(rows, r.timeIn, 6, PURPLE, Names::dim, Names::duration);
		}
		if (!r.distanceBy.isEmpty()) {
			rows.add(new Head("Travelled " + n(r.stat("distance")) + " blocks"));
			bars(rows, r.distanceBy, 6, TEAL, DetailPanel::cap, v -> n(v));
		}
		if (r.stats.containsKey("highestVisited")) rows.add(new Pair("Highest point", "Y " + n(r.stat("highestVisited"))));
		if (r.stats.containsKey("lowestVisited")) rows.add(new Pair("Lowest point", "Y " + n(r.stat("lowestVisited"))));

		boolean env = false;
		for (String k : new String[] {"underwaterSec", "lavaSec", "fireSec", "rainSec", "stormSec", "nightSec", "darkSec"}) if (r.stat(k) > 0) env = true;
		if (env) {
			rows.add(new Head("Conditions"));
			time(rows, r, "nightSec", "At night");
			time(rows, r, "darkSec", "In total darkness");
			time(rows, r, "underwaterSec", "Underwater");
			time(rows, r, "rainSec", "In the rain");
			time(rows, r, "stormSec", "In thunderstorms");
			time(rows, r, "fireSec", "On fire");
			time(rows, r, "lavaSec", "Swimming in lava");
		}
		if (!r.biomes.isEmpty()) {
			rows.add(new Head(r.biomes.size() + (r.biomes.size() == 1 ? " biome visited" : " biomes visited")));
			List<String> bs = new ArrayList<>();
			for (String b : r.biomes) bs.add(biome(b));
			rows.add(new Text(String.join(", ", bs), DIM));
		}
		if (!r.minedBiome.isEmpty()) {
			rows.add(new Head("Where it mined"));
			bars(rows, r.minedBiome, 6, GREEN, DetailPanel::biome, Names::num);
			if (r.minedDim.size() > 1) bars(rows, r.minedDim, 3, PURPLE, Names::dim, Names::num);
		}
		boolean extras = false;
		for (String k : new String[] {"jumps", "xp", "trades", "bred", "sleeps", "raids", "cake", "records", "bells", "targets"}) if (r.stat(k) > 0) extras = true;
		if (extras) {
			rows.add(new Head("While you had it"));
			pair(rows, r, "jumps", "Jumps");
			pair(rows, r, "xp", "XP collected");
			pair(rows, r, "trades", "Villager trades");
			pair(rows, r, "bred", "Animals bred");
			pair(rows, r, "sleeps", "Nights slept");
			pair(rows, r, "raids", "Raids won");
			pair(rows, r, "bells", "Bells rung");
			pair(rows, r, "targets", "Targets hit");
			pair(rows, r, "records", "Music discs played");
			pair(rows, r, "cake", "Cake slices eaten");
		}
		if (rows.isEmpty()) rows.add(new Text("It hasn't been anywhere yet.", DIM));
		return rows;
	}

	private List<Row> family(ItemRecord r) {
		List<Row> rows = new ArrayList<>();
		Map<String, Double> mats = new LinkedHashMap<>();
		Map<String, List<String>> matWhat = new LinkedHashMap<>();
		List<Row> other = new ArrayList<>();
		for (ItemRecord.Link l : r.parents) {
			ItemRecord p = ClientArchive.get(l.from);
			String pn = p != null ? p.name : "a lost item";
			switch (l.type) {
				case "material" -> {
					mats.merge(l.from, l.amount, Double::sum);
					matWhat.computeIfAbsent(l.from, k -> new ArrayList<>());
					if (!matWhat.get(l.from).contains(l.what)) matWhat.get(l.from).add(l.what);
				}
				case "successor" -> other.add(new Link("Heir of " + pn + (p != null && p.isDead() ? " ☠" : ""), l.from));
				case "anvil" -> other.add(new Link("Absorbed " + pn + " in an anvil", l.from));
				case "merge" -> other.add(new Link("Made by merging " + pn, l.from));
				case "twin" -> other.add(new Link("A copy of " + pn, l.from));
				default -> {
				}
			}
		}
		if (!mats.isEmpty()) {
			rows.add(new Head("Made from"));
			for (Map.Entry<String, Double> m : mats.entrySet()) {
				ItemRecord p = ClientArchive.get(m.getKey());
				String pn = p != null ? p.name : "a lost item";
				String amt = Math.abs(m.getValue() - Math.round(m.getValue())) < 0.05 ? String.valueOf(Math.round(m.getValue())) : String.format(Locale.US, "%.1f", m.getValue());
				rows.add(new Link(amt + " " + String.join("/", matWhat.get(m.getKey())) + " gathered by " + pn, m.getKey()));
			}
		}
		if (!other.isEmpty()) {
			rows.add(new Head("Ancestors"));
			rows.addAll(other);
		}
		List<Row> kids = new ArrayList<>();
		int generation = generations(r, 0, new java.util.HashSet<>());
		for (ItemRecord c : ClientArchive.RECORDS.values()) {
			for (ItemRecord.Link l : c.parents) {
				if (!r.id.equals(l.from)) continue;
				String what = switch (l.type) {
					case "material" -> "Gathered materials for ";
					case "successor" -> "Succeeded by ";
					case "anvil" -> "Gave its life to ";
					case "merge" -> "Merged into ";
					default -> "Related to ";
				};
				kids.add(new Link(what + c.name, c.id));
				break;
			}
		}
		if (!kids.isEmpty()) {
			rows.add(new Head("Descendants"));
			rows.addAll(kids);
		}
		if (generation > 0) rows.add(new Pair("Generation", String.valueOf(generation + 1)));
		if (rows.isEmpty()) rows.add(new Text("No family yet. Craft with materials this tool gathered and its children appear here.", DIM));
		return rows;
	}

	/** How many ancestors deep this item's family line goes. */
	private static int generations(ItemRecord r, int depth, java.util.Set<String> seen) {
		if (depth > 50 || !seen.add(r.id)) return depth;
		int best = depth;
		for (ItemRecord.Link l : r.parents) {
			ItemRecord p = ClientArchive.get(l.from);
			if (p != null) best = Math.max(best, generations(p, depth + 1, seen));
		}
		return best;
	}

	// ---------------------------------------------------------------- hall of records

	void drawRecords(GuiGraphicsExtractor g, int x, int y, int w, int h, int mouseX, int mouseY) {
		Font font = screen.font();
		if (lastId != null) {
			lastId = null;
			scroll = 0;
		}
		tabHits.clear();
		frame(g, x, y, w, h);
		g.text(font, "HALL OF RECORDS", x + 8, y + 9, GOLD, true);
		g.text(font, "Click a name to visit its star", x + 8, y + 21, DIM, false);
		drawRows(g, records(), x, y + 36, w, y + h - 6, mouseX, mouseY);
	}

	private record Rec(String title, ToDoubleFunction<ItemRecord> metric, boolean onlyDead, boolean onlyAlive, java.util.function.Function<Double, String> fmt) {
	}

	private static double ageDays(ItemRecord r) {
		long end = r.death != null ? r.death.at : System.currentTimeMillis();
		return (end - r.bornAt) / 60000.0;
	}

	private List<Row> records() {
		List<Row> rows = new ArrayList<>();
		List<ItemRecord> all = new ArrayList<>(ClientArchive.RECORDS.values());
		if (all.isEmpty()) {
			rows.add(new Text("Nothing recorded yet.", DIM));
			return rows;
		}
		long dead = all.stream().filter(ItemRecord::isDead).count();
		long mined = 0, kills = 0;
		double distance = 0;
		Map<String, Long> causes = new HashMap<>();
		double lifeSum = 0;
		for (ItemRecord r : all) {
			mined += r.totalMined();
			kills += r.totalKills();
			distance += r.stat("distance");
			if (r.isDead() && r.death != null) {
				causes.merge(r.death.cause, 1L, Long::sum);
				lifeSum += ageDays(r);
			}
		}
		rows.add(new Head("Your armory"));
		rows.add(new Pair("Items remembered", n(all.size())));
		rows.add(new Pair("Still with you", n(all.size() - dead)));
		rows.add(new Pair("Fallen", n(dead)));
		if (dead > 0) rows.add(new Pair("Average lifespan", Names.duration(lifeSum / dead * 60)));
		rows.add(new Pair("Blocks mined, all gear", n(mined)));
		rows.add(new Pair("Kills, all gear", n(kills)));
		rows.add(new Pair("Distance travelled, all gear", n(distance)));
		if (!causes.isEmpty()) {
			rows.add(new Head("Causes of death"));
			bars(rows, causes, 8, 0xFF9A9AAA, DetailPanel::causeName, Names::num);
		}

		Rec[] recs = {
				new Rec("Longest-lived", DetailPanel::ageDays, false, false, v -> Names.duration(v * 60)),
				new Rec("Most blocks mined", r -> r.totalMined(), false, false, Names::num),
				new Rec("Most diamonds", r -> r.stat("diamonds"), false, false, Names::num),
				new Rec("Most kills", r -> r.totalKills(), false, false, Names::num),
				new Rec("Biggest hit", r -> r.stat("biggestHit") / 2, false, false, v -> String.format(Locale.US, "%.1f♥", v)),
				new Rec("Farthest kill", r -> r.stat("longestKill"), false, false, v -> n(v) + " m"),
				new Rec("Most damage absorbed", r -> r.stat("absorbed") / 2, false, false, v -> n(v) + "♥"),
				new Rec("Most attacks blocked", r -> r.stat("blocked"), false, false, Names::num),
				new Rec("Most travelled", r -> r.stat("distance"), false, false, v -> n(v) + " m"),
				new Rec("Most flown", r -> r.stat("flightDist"), false, false, v -> n(v) + " m"),
				new Rec("Most repaired", r -> r.stat("mendingRepaired") + r.stat("repairsAnvil") * 100, false, false, v -> ""),
				new Rec("Most XP levels spent", r -> r.stat("levelsSpent"), false, false, Names::num),
				new Rec("Survived most of your deaths", r -> r.stat("deathsWorn") + r.stat("deathsHeld"), false, false, Names::num),
				new Rec("Most biomes seen", r -> r.biomes.size(), false, false, Names::num),
				new Rec("Biggest family", r -> r.parents.size(), false, false, v -> n(v) + " links"),
		};
		rows.add(new Head("Records"));
		for (Rec rec : recs) {
			ItemRecord best = null;
			double bv = 0;
			for (ItemRecord r : all) {
				double v = rec.metric.applyAsDouble(r);
				if (v > bv) {
					bv = v;
					best = r;
				}
			}
			if (best == null) continue;
			String val = rec.fmt.apply(bv);
			rows.add(new Text(rec.title + (val.isEmpty() ? "" : ": " + val), DIM));
			rows.add(new Link(best.name + (best.isDead() ? " ☠" : ""), best.id));
		}
		ItemRecord shortest = all.stream().filter(ItemRecord::isDead).min(Comparator.comparingDouble(DetailPanel::ageDays)).orElse(null);
		if (shortest != null) {
			rows.add(new Text("Gone too soon: " + Names.duration(ageDays(shortest) * 60), DIM));
			rows.add(new Link(shortest.name + " ☠", shortest.id));
		}
		return rows;
	}

	private static String causeName(String c) {
		return switch (c) {
			case "broke" -> "Worn out";
			case "lava" -> "Lava";
			case "fire" -> "Fire";
			case "cactus" -> "Cactus";
			case "explosion" -> "Explosions";
			case "lightning" -> "Lightning";
			case "void" -> "The void";
			case "despawned" -> "Forgotten";
			case "lost" -> "Lost after a death";
			case "sacrificed" -> "Anvil sacrifice";
			case "merged" -> "Merged";
			default -> cap(c);
		};
	}
}
