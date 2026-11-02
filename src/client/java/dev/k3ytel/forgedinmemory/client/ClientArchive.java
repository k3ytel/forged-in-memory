package dev.k3ytel.forgedinmemory.client;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import dev.k3ytel.forgedinmemory.ForgedInMemory;
import dev.k3ytel.forgedinmemory.core.ItemRecord;
import dev.k3ytel.forgedinmemory.net.Net;

/** The client's copy of your item records, sent by the server (or built by the vanilla-server tracker). */
public final class ClientArchive {
	private static final Gson GSON = new Gson();
	private static final Type LIST = new TypeToken<List<ItemRecord>>() {
	}.getType();

	public static final Map<String, ItemRecord> RECORDS = new LinkedHashMap<>();
	/** True once the server sent us anything: it runs Forged in Memory. */
	public static boolean serverHasMod;
	/** Bumped on every change so screens can refresh. */
	public static int revision;

	private ClientArchive() {
	}

	public static void onSync(Net.SyncPayload p) {
		List<ItemRecord> list;
		try {
			list = GSON.fromJson(Net.gunzip(p.gzJson()), LIST);
		} catch (Exception e) {
			ForgedInMemory.LOGGER.error("[Forged in Memory] Bad sync packet", e);
			return;
		}
		serverHasMod = true;
		if (p.full()) RECORDS.clear();
		for (ItemRecord r : list) {
			ItemRecord old = RECORDS.put(r.id, r);
			if (!p.full()) Milestones.check(old, r);
		}
		revision++;
	}

	public static void clear() {
		RECORDS.clear();
		serverHasMod = false;
		revision++;
	}

	public static ItemRecord get(String id) {
		return id == null ? null : RECORDS.get(id);
	}
}
