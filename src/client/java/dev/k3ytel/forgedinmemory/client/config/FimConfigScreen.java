package dev.k3ytel.forgedinmemory.client.config;

import java.awt.Color;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import dev.isxander.yacl3.api.Binding;
import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.StateManager;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.FloatSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import dev.k3ytel.forgedinmemory.client.gui.TreeScreen;
import dev.k3ytel.forgedinmemory.core.FimConfig;
import dev.k3ytel.forgedinmemory.core.FimConfig.CrossLinks;
import dev.k3ytel.forgedinmemory.core.FimConfig.Effects;
import dev.k3ytel.forgedinmemory.core.FimConfig.Labels;
import dev.k3ytel.forgedinmemory.core.FimConfig.Theme;
import dev.k3ytel.forgedinmemory.core.FimConfig.Tone;
import dev.k3ytel.forgedinmemory.core.FimConfig.TooltipMode;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The config screen (YACL), opened from Mod Menu or the ⚙ button on the tree. */
public final class FimConfigScreen {
	private FimConfigScreen() {
	}

	public static Screen create(Screen parent) {
		FimConfig cfg = FimConfig.get();
		FimConfig def = new FimConfig();

		ConfigCategory notifications = ConfigCategory.createBuilder()
				.name(Component.literal("Notifications"))
				.group(OptionGroup.createBuilder()
						.name(Component.literal("When an item dies"))
						.option(bool("Toast", "A toast with the item's name and where it died.", def.deathToast, () -> cfg.deathToast, v -> cfg.deathToast = v))
						.option(bool("Chat obituary", "A few lines in chat.\n• Cause of death and epitaph\n• Where it died (click the coordinates to copy them)\n• Age, best stat and witnesses",
								def.chatObituary, () -> cfg.chatObituary, v -> cfg.chatObituary = v))
						.option(bool("Bell", "A soft bell tolls.", def.bell, () -> cfg.bell, v -> cfg.bell = v))
						.option(intSlider("Bell volume", "How loud the bell is.", def.bellVolume, () -> cfg.bellVolume, v -> cfg.bellVolume = v, 0, 100, 5, v -> v + "%"))
						.option(bool("Ghost", "The item's ghost floats up from where it died, with soul particles.", def.ghost, () -> cfg.ghost, v -> cfg.ghost = v))
						.option(enumOption("Epitaph tone", "How obituaries are written.\n• Auto: respectful normally, epic for battle deaths, a roast for silly deaths\n• Or always one style",
								Tone.class, def.tone, () -> cfg.tone, v -> cfg.tone = v, t -> Component.literal(t.displayName)))
						.build())
				.group(OptionGroup.createBuilder()
						.name(Component.literal("Milestones"))
						.option(bool("Milestone toasts", "A toast when an item crosses a round number.\n• 1,000 blocks mined, 100 kills, first diamond...",
								def.milestones, () -> cfg.milestones, v -> cfg.milestones = v))
						.option(bool("Milestone chime", "An amethyst chime with milestone toasts.", def.milestoneChime, () -> cfg.milestoneChime, v -> cfg.milestoneChime = v))
						.build())
				.group(OptionGroup.createBuilder()
						.name(Component.literal("Tooltips"))
						.option(enumOption("Item tooltip", "What tracked items show in their tooltip.\n• One line: age, blocks, kills (hold Shift for more)\n• Always detailed: the full summary every time\n• Off: vanilla tooltips",
								TooltipMode.class, def.tooltip, () -> cfg.tooltip, v -> cfg.tooltip = v, t -> Component.literal(t.displayName)))
						.build())
				.build();

		OptionGroup.Builder colors = OptionGroup.createBuilder()
				.name(Component.literal("Custom colours"))
				.collapsed(true)
				.option(bool("Use custom colours", "Use the colours below instead of the theme's.", def.customColors, () -> cfg.customColors, v -> cfg.customColors = v));
		for (int i = 0; i < TreeScreen.GROUPS.length; i++) {
			final int idx = i;
			colors.option(color(TreeScreen.GROUPS[i].title(), "Colour of the " + TreeScreen.GROUPS[i].title() + " constellation.",
					new Color(Theme.SKYRIM.palette[idx] & 0xFFFFFF), () -> new Color(cfg.colors[idx] & 0xFFFFFF), v -> cfg.colors[idx] = 0xFF000000 | (v.getRGB() & 0xFFFFFF)));
		}
		colors.option(ButtonOption.createBuilder()
				.name(Component.literal("Copy the theme's colours"))
				.description(desc("Fills the colours above with the current theme's colours, as a starting point.\n• Reopen this screen to see them"))
				.text(Component.literal("Copy"))
				.action((s, opt) -> {
					cfg.colors = cfg.theme.palette.clone();
					FimConfig.save();
				})
				.build());

		ConfigCategory tree = ConfigCategory.createBuilder()
				.name(Component.literal("Star sky"))
				.group(OptionGroup.createBuilder()
						.name(Component.literal("Look"))
						.option(enumOption("Theme", "Colours of the constellations, the nebula and the gold accents.\n• Skyrim: warm tools, red blades, blue armor\n• Ember, Frost, Void, Aurora, Monochrome",
								Theme.class, def.theme, () -> cfg.theme, v -> cfg.theme = v, t -> Component.literal(t.displayName)))
						.option(floatSlider("Star brightness", "How strongly the stars glow.", def.starBrightness, () -> cfg.starBrightness, v -> cfg.starBrightness = v, 0.3f, 1.8f, 0.05f, "%.2f×"))
						.option(floatSlider("Constellation art", "How visible the big outline drawings behind each constellation are.\n• 0 = hidden", def.artStrength, () -> cfg.artStrength, v -> cfg.artStrength = v, 0f, 2f, 0.05f, "%.2f×"))
						.option(floatSlider("Nebula", "How bright the coloured clouds in the background are.\n• 0 = plain night", def.nebula, () -> cfg.nebula, v -> cfg.nebula = v, 0f, 1.5f, 0.05f, "%.2f×"))
						.option(enumOption("Star names", "When item names show under the stars.", Labels.class, def.labels, () -> cfg.labels, v -> cfg.labels = v, l -> Component.literal(l.displayName)))
						.option(enumOption("Links to other constellations", "Lines like \"the axe that chopped this pickaxe's wood\".\n• When focused: only for the star you hover or click (tidy)\n• Always: all of them, faintly",
								CrossLinks.class, def.crossLinks, () -> cfg.crossLinks, v -> cfg.crossLinks = v, c -> Component.literal(c.displayName)))
						.build())
				.group(OptionGroup.createBuilder()
						.name(Component.literal("Effects"))
						.option(enumOption("Effects level", "Overall amount of eye candy.\n• Reduced: no shooting stars or dust\n• Minimal: also no aurora, Milky Way, second nebula or orbiting motes (for slow PCs)",
								Effects.class, def.effects, () -> cfg.effects, v -> cfg.effects = v, e -> Component.literal(e.displayName)))
						.option(bool("Aurora", "Shimmering curtains along the top of the sky.", def.aurora, () -> cfg.aurora, v -> cfg.aurora = v))
						.option(bool("Shooting stars", "Every few seconds.", def.shootingStars, () -> cfg.shootingStars, v -> cfg.shootingStars = v))
						.option(bool("Milky Way", "A band of stars across the sky.", def.milkyWay, () -> cfg.milkyWay, v -> cfg.milkyWay = v))
						.option(bool("Floating dust", "Soft motes drifting in front.", def.dust, () -> cfg.dust, v -> cfg.dust = v))
						.option(bool("Opening animation", "Fade in, camera glide, stars popping in one constellation at a time.", def.intro, () -> cfg.intro, v -> cfg.intro = v))
						.build())
				.group(OptionGroup.createBuilder()
						.name(Component.literal("Screen"))
						.option(bool("Star chart", "The minimap in the bottom-left corner.", def.minimap, () -> cfg.minimap, v -> cfg.minimap = v))
						.option(bool("Inventory button", "The small ✦ button next to the inventory.\n• The key (Home by default) always works", def.inventoryButton, () -> cfg.inventoryButton, v -> cfg.inventoryButton = v))
						.option(bool("Sounds", "Chimes when opening the sky, hovering and clicking stars.", def.sounds, () -> cfg.sounds, v -> cfg.sounds = v))
						.option(intSlider("Sound volume", "How loud those chimes are.", def.soundVolume, () -> cfg.soundVolume, v -> cfg.soundVolume = v, 0, 100, 5, v -> v + "%"))
						.build())
				.group(colors.build())
				.build();

		ConfigCategory tracking = ConfigCategory.createBuilder()
				.name(Component.literal("Tracking"))
				.option(intSlider("Missing gear counts as lost after", "When you die, dropped gear is \"missing\".\n• Picked up in time: it survives\n• Not picked up by then: it dies (lost)\n• Applies in singleplayer and on servers you host with this mod",
						def.missingMinutes, () -> cfg.missingMinutes, v -> cfg.missingMinutes = v, 1, 60, 1, v -> v + " min"))
				.option(ButtonOption.createBuilder()
						.name(Component.literal("Open the star sky").withStyle(ChatFormatting.AQUA))
						.description(desc("Opens your gear's family tree."))
						.text(Component.literal("Open"))
						.action((s, opt) -> {
							FimConfig.save();
							Minecraft mc = Minecraft.getInstance();
							if (mc.player != null) mc.gui.setScreen(new TreeScreen(null));
						})
						.build())
				.build();

		return YetAnotherConfigLib.createBuilder()
				.title(Component.literal("Forged in Memory"))
				.category(notifications)
				.category(tree)
				.category(tracking)
				.save(FimConfig::save)
				.build()
				.generateScreen(parent);
	}

	private static OptionDescription desc(String text) {
		return OptionDescription.of(Component.literal(text));
	}

	private static Option<Boolean> bool(String name, String description, boolean def, Supplier<Boolean> get, Consumer<Boolean> set) {
		return Option.<Boolean>createBuilder()
				.name(Component.literal(name))
				.description(desc(description))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(TickBoxControllerBuilder::create)
				.build();
	}

	private static Option<Integer> intSlider(String name, String description, int def, Supplier<Integer> get, Consumer<Integer> set,
											 int min, int max, int step, Function<Integer, String> format) {
		return Option.<Integer>createBuilder()
				.name(Component.literal(name))
				.description(desc(description))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(opt -> IntegerSliderControllerBuilder.create(opt)
						.range(min, max).step(step)
						.formatValue(v -> Component.literal(format.apply(v))))
				.build();
	}

	private static Option<Float> floatSlider(String name, String description, float def, Supplier<Float> get, Consumer<Float> set,
											 float min, float max, float step, String format) {
		return Option.<Float>createBuilder()
				.name(Component.literal(name))
				.description(desc(description))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(opt -> FloatSliderControllerBuilder.create(opt)
						.range(min, max).step(step)
						.formatValue(v -> Component.literal(String.format(Locale.ROOT, format, v))))
				.build();
	}

	private static Option<Color> color(String name, String description, Color def, Supplier<Color> get, Consumer<Color> set) {
		return Option.<Color>createBuilder()
				.name(Component.literal(name))
				.description(desc(description))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(ColorControllerBuilder::create)
				.build();
	}

	private static <E extends Enum<E>> Option<E> enumOption(String name, String description, Class<E> type, E def,
															 Supplier<E> get, Consumer<E> set, Function<E, Component> display) {
		return Option.<E>createBuilder()
				.name(Component.literal(name))
				.description(desc(description))
				.stateManager(StateManager.createInstant(Binding.generic(def, get, set)))
				.controller(opt -> EnumControllerBuilder.create(opt)
						.enumClass(type)
						.formatValue(display::apply))
				.build();
	}
}
