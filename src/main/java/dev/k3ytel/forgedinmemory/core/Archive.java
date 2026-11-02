package dev.k3ytel.forgedinmemory.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything saved for one world: all item records, the material ledger and pending heirs. */
public class Archive {
	public int version = 1;
	public Map<String, ItemRecord> records = new LinkedHashMap<>();
	/** player uuid -> material family -> FIFO of (tool id, units) */
	public Map<String, Map<String, List<LedgerEntry>>> ledger = new HashMap<>();
	/** player uuid -> kind -> id of the last item of that kind that died */
	public Map<String, Map<String, String>> heirs = new HashMap<>();
	/** players seen at least once (their first scan marks items as "already owned") */
	public Set<String> knownPlayers = new HashSet<>();

	public static class LedgerEntry {
		public String tool;
		public double units;

		public LedgerEntry() {
		}

		public LedgerEntry(String tool, double units) {
			this.tool = tool;
			this.units = units;
		}
	}

	public List<ItemRecord> recordsOf(String owner) {
		List<ItemRecord> out = new ArrayList<>();
		for (ItemRecord r : records.values()) {
			if (owner.equals(r.owner) || r.owners.contains(owner)) out.add(r);
		}
		return out;
	}
}
