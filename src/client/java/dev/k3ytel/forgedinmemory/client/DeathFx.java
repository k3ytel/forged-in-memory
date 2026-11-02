package dev.k3ytel.forgedinmemory.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.k3ytel.forgedinmemory.core.FimConfig;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import dev.k3ytel.forgedinmemory.net.Net;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** What you see and hear when one of your items dies: toast with the place, a chat obituary, bell, and its ghost. */
public final class DeathFx {
	private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(7000L);
	private static final int FULL_BRIGHT = 15728880;
	private static final float GHOST_SECONDS = 3.0f;


	private record Ghost(ItemStack stack, Vec3 pos, long start, ItemStackRenderState state) {
	}

	private static final List<Ghost> GHOSTS = new ArrayList<>();

	private DeathFx() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(DeathFx::render);
		ClientTickEvents.END_CLIENT_TICK.register(DeathFx::tick);
	}

	public static void onDeath(Net.DeathPayload p) {
		Minecraft mc = Minecraft.getInstance();
		ItemRecord r = ClientArchive.get(p.id());
		String name = r != null ? r.name : "an item";
		int x = (int) Math.floor(p.x()), y = (int) Math.floor(p.y()), z = (int) Math.floor(p.z());
		if (r != null && r.death != null && r.death.pos != null) {
			x = r.death.pos[0];
			y = r.death.pos[1];
			z = r.death.pos[2];
		}
		FimConfig cfg = FimConfig.get();
		if (cfg.deathToast) {
			String where = "Died at " + x + ", " + y + ", " + z + " · " + shortDim(p.dim());
			SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.literal("☠ RIP " + name), Component.literal(where));
		}
		if (cfg.chatObituary && r != null) chatObituary(mc, r, x, y, z);
		if (cfg.bell && cfg.bellVolume > 0) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BELL_BLOCK, 0.8f, cfg.bellVolume / 100f));
		if (cfg.ghost && r != null && mc.level != null && mc.level.dimension().identifier().toString().equals(p.dim())) {
			ItemStack s = Names.stack(r.item);
			if (!s.isEmpty()) GHOSTS.add(new Ghost(s, new Vec3(p.x(), p.y() + 0.5, p.z()), System.nanoTime(), new ItemStackRenderState()));
		}
	}

	private static String shortDim(String dim) {
		return switch (dim) {
			case "minecraft:overworld" -> "Overworld";
			case "minecraft:the_nether" -> "Nether";
			case "minecraft:the_end" -> "End";
			default -> dim;
		};
	}

	private static void chatObituary(Minecraft mc, ItemRecord r, int x, int y, int z) {
		String coords = x + " " + y + " " + z;
		MutableComponent line = Component.literal("☠ ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(r.name).withStyle(ChatFormatting.GOLD))
				.append(Component.literal(", " + Titles.of(r)).withStyle(ChatFormatting.LIGHT_PURPLE))
				.append(Component.literal(" has fallen. ").withStyle(ChatFormatting.GRAY))
				.append(Component.literal(Obituary.causeLine(r)).withStyle(ChatFormatting.GRAY));
		mc.gui.hud.getChat().addClientSystemMessage(line);

		MutableComponent where = Component.literal("   Where: ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal("[" + x + ", " + y + ", " + z + "]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
						.withClickEvent(new ClickEvent.CopyToClipboard(coords))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy the coordinates")))));
		String place = Story.place(r.death);
		if (place != null) {
			int i = place.indexOf(" in ");
			where.append(Component.literal(i >= 0 ? place.substring(i) : "").withStyle(ChatFormatting.GRAY));
		}
		double fromBirth = Story.distanceFromBirth(r);
		if (fromBirth >= 0) where.append(Component.literal(", " + Names.num(fromBirth) + " blocks from where it was born").withStyle(ChatFormatting.DARK_GRAY));
		mc.gui.hud.getChat().addClientSystemMessage(where);

		MutableComponent life = Component.literal("   Lived " + Names.age(r) + " · " + Obituary.headline(r)).withStyle(ChatFormatting.DARK_GRAY);
		if (r.death != null && !r.death.witnesses.isEmpty()) {
			life.append(Component.literal(" · Witnessed by " + String.join(", ", r.death.witnesses)).withStyle(ChatFormatting.DARK_GRAY));
		}
		mc.gui.hud.getChat().addClientSystemMessage(life);
		mc.gui.hud.getChat().addClientSystemMessage(Component.literal("   “" + Obituary.epitaph(r) + "”").withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
	}

	private static float seconds(Ghost g) {
		return (System.nanoTime() - g.start) / 1e9f;
	}

	private static void tick(Minecraft mc) {
		if (GHOSTS.isEmpty()) return;
		if (mc.level == null) {
			GHOSTS.clear();
			return;
		}
		for (Iterator<Ghost> it = GHOSTS.iterator(); it.hasNext();) {
			Ghost g = it.next();
			float t = seconds(g);
			if (t > GHOST_SECONDS) {
				it.remove();
				continue;
			}
			if (mc.level.getRandom().nextFloat() < 0.5f) {
				double y = g.pos.y + rise(t);
				mc.level.addParticle(ParticleTypes.SOUL, g.pos.x + (mc.level.getRandom().nextDouble() - 0.5) * 0.4, y,
						g.pos.z + (mc.level.getRandom().nextDouble() - 0.5) * 0.4, 0, 0.02, 0);
			}
		}
	}

	private static double rise(float t) {
		return t * 0.6;
	}

	private static void render(LevelRenderContext ctx) {
		if (GHOSTS.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		CameraRenderState cam = ctx.levelState().cameraRenderState;
		PoseStack pose = ctx.poseStack();
		for (Ghost g : GHOSTS) {
			float t = seconds(g);
			if (t > GHOST_SECONDS) continue;
			float fadeIn = Math.min(1f, t / 0.3f);
			float fadeOut = Math.min(1f, (GHOST_SECONDS - t) / 0.8f);
			float scale = 0.9f * fadeIn * fadeOut;
			if (scale < 0.02f) continue;
			mc.getItemModelResolver().updateForNonLiving(g.state, g.stack, ItemDisplayContext.GROUND, mc.player);
			pose.pushPose();
			pose.translate(g.pos.x - cam.pos.x, g.pos.y + rise(t) - cam.pos.y, g.pos.z - cam.pos.z);
			pose.rotate(com.mojang.math.Axis.YP, t * 2.5f);
			pose.scale(scale * 1.6f, scale * 1.6f, scale * 1.6f);
			g.state.submit(pose, ctx.submitNodeCollector(), FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
			pose.popPose();
		}
	}
}
