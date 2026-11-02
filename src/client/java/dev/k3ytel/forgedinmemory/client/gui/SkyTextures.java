package dev.k3ytel.forgedinmemory.client.gui;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;

import dev.k3ytel.forgedinmemory.ForgedInMemory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

/**
 * Textures for the night sky, made in code once: soft glow, star sparkle, ring, two nebula layers,
 * and glowing constellation outline art built from Minecraft's own item textures.
 */
public final class SkyTextures {
	public static final Identifier GLOW = ForgedInMemory.id("dynamic/glow");
	public static final Identifier SPARKLE = ForgedInMemory.id("dynamic/sparkle");
	public static final Identifier RING = ForgedInMemory.id("dynamic/ring");
	public static final Identifier NEBULA = ForgedInMemory.id("dynamic/nebula");
	public static final Identifier NEBULA2 = ForgedInMemory.id("dynamic/nebula2");
	public static final int GLOW_SIZE = 64;
	public static final int SPARKLE_SIZE = 64;
	public static final int RING_SIZE = 128;
	public static final int NEBULA_SIZE = 512;
	public static final int ART_SIZE = 256;

	private static boolean made;
	private static final Map<String, Identifier> ART = new HashMap<>();

	private SkyTextures() {
	}

	public static void ensure() {
		if (made) return;
		made = true;
		Minecraft mc = Minecraft.getInstance();
		mc.getTextureManager().register(GLOW, make("glow", GLOW_SIZE, SkyTextures::glow));
		mc.getTextureManager().register(SPARKLE, make("sparkle", SPARKLE_SIZE, SkyTextures::sparkle));
		mc.getTextureManager().register(RING, make("ring", RING_SIZE, SkyTextures::ring));
		mc.getTextureManager().register(NEBULA, makeNebula(1337, false));
		mc.getTextureManager().register(NEBULA2, makeNebula(4242, true));
	}

	private interface Pixel {
		int argb(double nx, double ny);
	}

	private static DynamicTexture make(String name, int size, Pixel p) {
		Smooth t = new Smooth("Forged in Memory " + name, size);
		NativeImage img = t.getPixels();
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				double nx = (x + 0.5) / size * 2 - 1;
				double ny = (y + 0.5) / size * 2 - 1;
				img.setPixel(x, y, p.argb(nx, ny));
			}
		}
		t.upload();
		return t;
	}

	private static int white(double a) {
		int ai = (int) Math.round(Math.max(0, Math.min(1, a)) * 255);
		return (ai << 24) | 0xFFFFFF;
	}

	private static int glow(double x, double y) {
		double d = Math.sqrt(x * x + y * y);
		if (d >= 1) return 0;
		return white(Math.pow(1 - d, 2.4));
	}

	private static int sparkle(double x, double y) {
		double d = Math.sqrt(x * x + y * y);
		double core = Math.pow(Math.max(0, 1 - d / 0.16), 1.5);
		double ax = Math.abs(x), ay = Math.abs(y);
		double rayH = Math.pow(Math.max(0, 1 - ay / 0.035), 2) * Math.pow(Math.max(0, 1 - ax), 1.6);
		double rayV = Math.pow(Math.max(0, 1 - ax / 0.035), 2) * Math.pow(Math.max(0, 1 - ay), 1.6);
		double halo = Math.pow(Math.max(0, 1 - d), 3) * 0.45;
		return white(Math.max(Math.max(core, halo), Math.max(rayH, rayV)));
	}

	private static int ring(double x, double y) {
		double d = Math.sqrt(x * x + y * y);
		double ring = Math.max(0, 1 - Math.abs(d - 0.86) / 0.035);
		// eight little tick marks around the ring, like a rune circle
		double ang = Math.atan2(y, x);
		double tick = Math.max(0, 1 - Math.abs(Math.sin(ang * 4)) / 0.08) * Math.max(0, 1 - Math.abs(d - 0.75) / 0.05);
		double softness = Math.max(0, 1 - Math.abs(d - 0.86) / 0.12) * 0.25;
		return white(Math.max(Math.max(ring, tick), softness));
	}

	// ---------------------------------------------------------------- nebulae

	private static DynamicTexture makeNebula(long seed, boolean clouds) {
		int n = NEBULA_SIZE;
		Smooth t = new Smooth("Forged in Memory nebula " + seed, n);
		NativeImage img = t.getPixels();
		Random rnd = new Random(seed);
		double[] grid = new double[3 * 17 * 17];
		for (int i = 0; i < grid.length; i++) grid[i] = rnd.nextDouble();
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				double u = (double) x / n, v = (double) y / n;
				double a = fbm(grid, 0, u * 4, v * 4, 6);
				double b = fbm(grid, 1, u * 3 + 5, v * 3 + 2, 6);
				double c = fbm(grid, 2, u * 6 + 1, v * 6 + 7, 5);
				// domain-warp for wispy shapes
				double w = fbm(grid, 0, u * 2 + b * 1.5, v * 2 + a * 1.5, 5);
				if (!clouds) {
					double r = 4 + 46 * Math.pow(b, 3) + 80 * Math.pow(c * w, 3);
					double g = 7 + 50 * Math.pow(a, 3) + 26 * Math.pow(w, 4);
					double bl = 20 + 96 * Math.pow(a, 2.2) + 46 * Math.pow(b, 2);
					img.setPixel(x, y, 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl));
				} else {
					double dens = Math.max(0, w * 1.6 - 0.62);
					double alpha = Math.min(1, dens * 2.2);
					// teal / magenta / gold wisps
					double hue = c;
					double r = 120 + 135 * Math.pow(hue, 2) * (1 - a);
					double g = 90 + 120 * a * (1 - hue);
					double bl = 160 + 95 * (1 - hue);
					img.setPixel(x, y, (clamp(alpha * 150) << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(bl));
				}
			}
		}
		t.upload();
		return t;
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, v));
	}

	private static double fbm(double[] grid, int layer, double x, double y, int octaves) {
		double sum = 0, amp = 0.5, freq = 1, norm = 0;
		for (int o = 0; o < octaves; o++) {
			sum += amp * noise(grid, layer, x * freq, y * freq);
			norm += amp;
			amp *= 0.5;
			freq *= 2;
		}
		return sum / norm;
	}

	private static double noise(double[] grid, int layer, double x, double y) {
		int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
		double fx = x - xi, fy = y - yi;
		double sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
		double v00 = g(grid, layer, xi, yi), v10 = g(grid, layer, xi + 1, yi);
		double v01 = g(grid, layer, xi, yi + 1), v11 = g(grid, layer, xi + 1, yi + 1);
		return (v00 * (1 - sx) + v10 * sx) * (1 - sy) + (v01 * (1 - sx) + v11 * sx) * sy;
	}

	private static double g(double[] grid, int layer, int x, int y) {
		return grid[layer * 17 * 17 + Math.floorMod(y, 16) * 17 + Math.floorMod(x, 16)];
	}

	// ---------------------------------------------------------------- constellation art

	/** Hand-drawn 16x16 shield (shields have no flat item texture). */
	private static final String[] SHIELD = {
			"................",
			"..############..",
			"..############..",
			"..############..",
			"..#####..#####..",
			"..#####..#####..",
			"..############..",
			"..############..",
			"...##########...",
			"...##########...",
			"....########....",
			"....########....",
			".....######.....",
			"......####......",
			".......##.......",
			"................"};

	/**
	 * Glowing outline art of an item texture (e.g. "iron_pickaxe"): thin bright edges along the pixel
	 * silhouette, star dots on its corners, a soft glow and a faint fill. White; tinted when drawn.
	 */
	public static Identifier art(String itemTexture) {
		Identifier cached = ART.get(itemTexture);
		if (cached != null) return cached;
		boolean[][] mask = new boolean[16][16];
		if ("shield".equals(itemTexture)) {
			for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) mask[y][x] = SHIELD[y].charAt(x) == '#';
		} else if (!loadMask(itemTexture, mask)) {
			ART.put(itemTexture, null);
			return null;
		}
		int n = ART_SIZE;
		double cell = n / 16.0;
		Smooth t = new Smooth("Forged in Memory art " + itemTexture, n);
		NativeImage img = t.getPixels();
		for (int py = 0; py < n; py++) {
			for (int px = 0; px < n; px++) {
				double fx = (px + 0.5) / cell, fy = (py + 0.5) / cell;
				int cx = (int) fx, cy = (int) fy;
				boolean inside = in(mask, cx, cy);
				double best = 99;
				double corner = 99;
				for (int oy = -1; oy <= 1; oy++) {
					for (int ox = -1; ox <= 1; ox++) {
						int gx = cx + ox, gy = cy + oy;
						boolean me = in(mask, gx, gy);
						// vertical edge on the left side of cell (gx,gy)
						if (me != in(mask, gx - 1, gy)) best = Math.min(best, segDist(fx, fy, gx, gy, gx, gy + 1));
						// horizontal edge on the top side of cell (gx,gy)
						if (me != in(mask, gx, gy - 1)) best = Math.min(best, segDist(fx, fy, gx, gy, gx + 1, gy));
						// outline corners -> star dots
						if (isCorner(mask, gx, gy)) corner = Math.min(corner, Math.hypot(fx - gx, fy - gy));
					}
				}
				double line = Math.max(0, 1 - best / 0.07);
				double glow = Math.pow(Math.max(0, 1 - best / 0.9), 3) * 0.35;
				double dot = Math.max(0, 1 - corner / 0.26);
				double dotGlow = Math.pow(Math.max(0, 1 - corner / 1.1), 2) * 0.75;
				double fill = inside ? 0.07 : 0;
				double a = Math.max(Math.max(line * 0.85, glow), Math.max(Math.max(dot, dotGlow), fill));
				img.setPixel(px, py, white(a));
			}
		}
		t.upload();
		Identifier id = ForgedInMemory.id("dynamic/art_" + itemTexture);
		Minecraft.getInstance().getTextureManager().register(id, t);
		ART.put(itemTexture, id);
		return id;
	}

	private static boolean loadMask(String itemTexture, boolean[][] mask) {
		Identifier res = Identifier.withDefaultNamespace("textures/item/" + itemTexture + ".png");
		try {
			Optional<Resource> r = Minecraft.getInstance().getResourceManager().getResource(res);
			if (r.isEmpty()) return false;
			try (InputStream in = r.get().open(); NativeImage src = NativeImage.read(in)) {
				int w = src.getWidth(), h = src.getHeight();
				for (int y = 0; y < 16; y++) {
					for (int x = 0; x < 16; x++) {
						int c = src.getPixel(x * w / 16, y * h / 16);
						mask[y][x] = ((c >>> 24) & 0xFF) > 40;
					}
				}
			}
			return true;
		} catch (Exception e) {
			ForgedInMemory.LOGGER.warn("[Forged in Memory] Could not read {} for constellation art", res, e);
			return false;
		}
	}

	private static boolean in(boolean[][] m, int x, int y) {
		return x >= 0 && y >= 0 && x < 16 && y < 16 && m[y][x];
	}

	/** A lattice point where the outline turns (not a straight run): a "star" of the constellation. */
	private static boolean isCorner(boolean[][] m, int x, int y) {
		boolean a = in(m, x - 1, y - 1), b = in(m, x, y - 1), c = in(m, x - 1, y), d = in(m, x, y);
		int count = (a ? 1 : 0) + (b ? 1 : 0) + (c ? 1 : 0) + (d ? 1 : 0);
		if (count == 1 || count == 3) return ((x * 7 + y * 13) % 2) == 0; // thin it out
		return false;
	}

	private static double segDist(double px, double py, double x1, double y1, double x2, double y2) {
		double dx = x2 - x1, dy = y2 - y1;
		double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)));
		return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
	}

	private static final class Smooth extends DynamicTexture {
		Smooth(String label, int size) {
			super(() -> label, size, size, false);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}
}
