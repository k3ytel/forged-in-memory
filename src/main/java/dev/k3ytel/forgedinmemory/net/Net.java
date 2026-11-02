package dev.k3ytel.forgedinmemory.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import dev.k3ytel.forgedinmemory.ForgedInMemory;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Packets between the server (which owns the archive) and the client (screens, toasts, tooltips). */
public final class Net {
	private static final int MAX_BYTES = 16 * 1024 * 1024;

	private Net() {
	}

	/** Server → client: records as gzipped JSON. full = replace the client's cache, else merge. */
	public record SyncPayload(boolean full, byte[] gzJson) implements CustomPacketPayload {
		public static final Type<SyncPayload> TYPE = new Type<>(ForgedInMemory.id("sync"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeBoolean(p.full);
					buf.writeByteArray(p.gzJson);
				},
				buf -> new SyncPayload(buf.readBoolean(), buf.readByteArray(MAX_BYTES)));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server → client: one of your items just died (record already synced). */
	public record DeathPayload(String id, String dim, double x, double y, double z) implements CustomPacketPayload {
		public static final Type<DeathPayload> TYPE = new Type<>(ForgedInMemory.id("death"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DeathPayload> CODEC = StreamCodec.of(
				(buf, p) -> {
					buf.writeUtf(p.id);
					buf.writeUtf(p.dim);
					buf.writeDouble(p.x);
					buf.writeDouble(p.y);
					buf.writeDouble(p.z);
				},
				buf -> new DeathPayload(buf.readUtf(), buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client → server: send me everything again (opening the tree screen). */
	public record RequestPayload() implements CustomPacketPayload {
		public static final Type<RequestPayload> TYPE = new Type<>(ForgedInMemory.id("request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, RequestPayload> CODEC = StreamCodec.of(
				(buf, p) -> buf.writeByte(0),
				buf -> {
					buf.readByte();
					return new RequestPayload();
				});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().registerLarge(SyncPayload.TYPE, SyncPayload.CODEC, MAX_BYTES + 1024);
		PayloadTypeRegistry.clientboundPlay().register(DeathPayload.TYPE, DeathPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(RequestPayload.TYPE, RequestPayload.CODEC);
	}

	public static byte[] gzip(String s) {
		try (ByteArrayOutputStream bos = new ByteArrayOutputStream(); GZIPOutputStream gz = new GZIPOutputStream(bos)) {
			gz.write(s.getBytes(StandardCharsets.UTF_8));
			gz.finish();
			return bos.toByteArray();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static String gunzip(byte[] b) {
		try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(b))) {
			return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
