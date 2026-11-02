package dev.k3ytel.forgedinmemory.core;

import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import dev.k3ytel.forgedinmemory.ForgedInMemory;
import net.fabricmc.loader.api.FabricLoader;

/** All settings, saved as config/forged-in-memory.json (client looks + the server's tracking option). */
public class FimConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FimConfig instance = new FimConfig();

	// ---- notifications
	public boolean deathToast = true;
	public boolean chatObituary = true;
	public boolean bell = true;
	public int bellVolume = 50;
	public boolean ghost = true;
	public boolean milestones = true;
	public boolean milestoneChime = true;
	public TooltipMode tooltip = TooltipMode.SHORT;
	public Tone tone = Tone.AUTO;

	// ---- tree screen
	public Theme theme = Theme.SKYRIM;
	public boolean customColors = false;
	/** one colour per constellation (same order as the tree's groups), used when customColors is on */
	public int[] colors = Theme.SKYRIM.palette.clone();
	public Effects effects = Effects.FULL;
	public boolean aurora = true;
	public boolean shootingStars = true;
	public boolean milkyWay = true;
	public boolean dust = true;
	public float nebula = 1.0f;
	public float starBrightness = 1.0f;
	public float artStrength = 1.0f;
	public boolean intro = true;
	public boolean sounds = true;
	public int soundVolume = 100;
	public boolean minimap = true;
	public boolean inventoryButton = true;
	public CrossLinks crossLinks = CrossLinks.ON_FOCUS;
	public Labels labels = Labels.AUTO;

	// ---- tracking (used by singleplayer and by servers that run the mod)
	public int missingMinutes = 5;

	public enum TooltipMode {
		OFF("Off"), SHORT("One line + Shift for more"), DETAILED("Always detailed");

		public final String displayName;

		TooltipMode(String n) {
			displayName = n;
		}
	}

	public enum Tone {
		AUTO("Auto (fits the death)"), RESPECTFUL("Respectful"), EPIC("Epic"), ROAST("Roast");

		public final String displayName;

		Tone(String n) {
			displayName = n;
		}
	}

	public enum Effects {
		FULL("Full"), REDUCED("Reduced"), MINIMAL("Minimal");

		public final String displayName;

		Effects(String n) {
			displayName = n;
		}
	}

	public enum CrossLinks {
		ON_FOCUS("When a star is focused"), ALWAYS("Always"), NEVER("Never");

		public final String displayName;

		CrossLinks(String n) {
			displayName = n;
		}
	}

	public enum Labels {
		AUTO("Auto (when zoomed in)"), ALWAYS("Always"), HOVER("Only on hover");

		public final String displayName;

		Labels(String n) {
			displayName = n;
		}
	}

	/** Colour themes: one colour per constellation, a nebula tint and an accent (titles, rings). */
	public enum Theme {
		SKYRIM("Skyrim", new int[] {0xFFF0985A, 0xFFE8B058, 0xFFD8BE80, 0xFFB8D068, 0xFFF0605A, 0xFF6AE09A, 0xFF6AB0F8, 0xFF5A9CF0,
				0xFF6A90E8, 0xFF7A88E8, 0xFF5AD0D8, 0xFFC08AF8, 0xFFA0E070}, 0xFFFFFFFF, 0xFFE8D7A8),
		EMBER("Ember", hues(0.0f, 0.13f, 0.62f, 1f), 0xFFFFB898, 0xFFFFD08A),
		FROST("Frost", hues(0.48f, 0.62f, 0.45f, 1f), 0xFFA8D8FF, 0xFFDDF2FF),
		VOID("Void", hues(0.72f, 0.92f, 0.55f, 1f), 0xFFC89AFF, 0xFFF0C0FF),
		AURORA("Aurora", hues(0.25f, 0.55f, 0.55f, 1f), 0xFFA0FFD8, 0xFFC8FFE8),
		MONO("Monochrome", grey(), 0xFF8A8A8A, 0xFFFFFFFF);

		public final String displayName;
		public final int[] palette;
		public final int nebulaTint;
		public final int accent;

		Theme(String n, int[] palette, int nebulaTint, int accent) {
			this.displayName = n;
			this.palette = palette;
			this.nebulaTint = nebulaTint;
			this.accent = accent;
		}

		private static int[] hues(float from, float to, float sat, float val) {
			int[] out = new int[13];
			for (int i = 0; i < 13; i++) {
				float h = from + (to - from) * ((i * 5) % 13) / 12f;
				out[i] = 0xFF000000 | (java.awt.Color.HSBtoRGB(h, sat, val) & 0xFFFFFF);
			}
			return out;
		}

		private static int[] grey() {
			int[] out = new int[13];
			for (int i = 0; i < 13; i++) {
				int v = 170 + (i * 7) % 80;
				out[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
			}
			return out;
		}
	}

	public static FimConfig get() {
		return instance;
	}

	public int groupColor(int index) {
		if (customColors && colors != null && index >= 0 && index < colors.length) return colors[index] | 0xFF000000;
		int[] p = theme.palette;
		return p[Math.floorMod(index, p.length)];
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("forged-in-memory.json");
	}

	public static void load() {
		Path f = file();
		if (Files.exists(f)) {
			try {
				FimConfig c = GSON.fromJson(Files.readString(f), FimConfig.class);
				if (c != null) instance = c;
			} catch (Exception e) {
				ForgedInMemory.LOGGER.warn("[Forged in Memory] Could not read {}, using defaults", f, e);
			}
		}
		instance.sanitize();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(instance));
		} catch (Exception e) {
			ForgedInMemory.LOGGER.warn("[Forged in Memory] Could not save the config", e);
		}
	}

	private void sanitize() {
		if (tooltip == null) tooltip = TooltipMode.SHORT;
		if (tone == null) tone = Tone.AUTO;
		if (theme == null) theme = Theme.SKYRIM;
		if (effects == null) effects = Effects.FULL;
		if (crossLinks == null) crossLinks = CrossLinks.ON_FOCUS;
		if (labels == null) labels = Labels.AUTO;
		if (colors == null || colors.length != 13) colors = Theme.SKYRIM.palette.clone();
		bellVolume = Math.max(0, Math.min(100, bellVolume));
		soundVolume = Math.max(0, Math.min(100, soundVolume));
		nebula = Math.max(0f, Math.min(1.5f, nebula));
		starBrightness = Math.max(0.3f, Math.min(1.8f, starBrightness));
		artStrength = Math.max(0f, Math.min(2f, artStrength));
		missingMinutes = Math.max(1, Math.min(120, missingMinutes));
	}

	// ---- effect switches the tree screen reads every frame

	public boolean showAurora() {
		return aurora && effects != Effects.MINIMAL;
	}

	public boolean showShootingStars() {
		return shootingStars && effects == Effects.FULL;
	}

	public boolean showMilkyWay() {
		return milkyWay && effects != Effects.MINIMAL;
	}

	public boolean showDust() {
		return dust && effects == Effects.FULL;
	}

	public boolean showExtras() {
		return effects != Effects.MINIMAL;
	}
}
