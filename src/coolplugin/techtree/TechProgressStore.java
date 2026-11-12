package coolplugin.techtree;

import arc.files.Fi;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.Jval;

/**
 * Persistent per-player server-side store saved at {@code config/alphystuff/techtree-progress.hjson}.
 * Tracks:
 * <ul>
 *   <li>Unlocked node IDs per player UUID</li>
 *   <li>Partial item contributions per node (`nodeId -> item -> paidAmount`), matching vanilla ResearchDialog's incremental spending</li>
 *   <li>Special condition flags granted to individual players (`flags`)</li>
 * </ul>
 */
public class TechProgressStore {

    public static class PlayerRecord {
        public String lastKnownName = "";
        public final ObjectSet<String> unlocked = new ObjectSet<>();
        public final ObjectSet<String> flags = new ObjectSet<>();
        public final ObjectMap<String, ObjectIntMap<String>> paidItems = new ObjectMap<>();
    }

    private final Fi saveFile;
    private final ObjectMap<String, PlayerRecord> records = new ObjectMap<>();

    public TechProgressStore(Fi saveFile) {
        this.saveFile = saveFile;
    }

    public synchronized PlayerRecord getOrCreate(String uuid, String playerName, TechNodeDef root) {
        PlayerRecord rec = records.get(uuid);
        boolean changed = false;
        if (rec == null) {
            rec = new PlayerRecord();
            records.put(uuid, rec);
            changed = true;
        }
        if (playerName != null) {
            String cleanName = arc.util.Strings.stripColors(playerName).trim();
            if (!cleanName.isEmpty() && !cleanName.equals(rec.lastKnownName)) {
                rec.lastKnownName = cleanName;
                changed = true;
            }
        }
        if (seedAlwaysUnlocked(rec, root)) {
            changed = true;
        }
        if (changed) save();
        return rec;
    }

    private boolean seedAlwaysUnlocked(PlayerRecord rec, TechNodeDef node) {
        if (node == null) return false;
        boolean added = false;
        if (node.alwaysUnlocked || node.virtual) {
            if (rec.unlocked.add(node.id)) added = true;
        }
        for (TechNodeDef child : node.children) {
            if (seedAlwaysUnlocked(rec, child)) added = true;
        }
        return added;
    }

    public synchronized void reseedAll(TechNodeDef root) {
        for (var entry : records.entries()) {
            seedAlwaysUnlocked(entry.value, root);
        }
        save();
    }

    public synchronized boolean isUnlocked(String uuid, TechNodeDef node) {
        if (node == null) return false;
        if (node.alwaysUnlocked || node.virtual) return true;
        PlayerRecord rec = records.get(uuid);
        return rec != null && rec.unlocked.contains(node.id);
    }

    public synchronized boolean isUnlocked(String uuid, String nodeId) {
        PlayerRecord rec = records.get(uuid);
        return rec != null && rec.unlocked.contains(nodeId);
    }

    public synchronized boolean hasFlag(String uuid, String flag) {
        PlayerRecord rec = records.get(uuid);
        return rec != null && rec.flags.contains(flag);
    }

    public synchronized void setFlag(String uuid, String playerName, String flag, boolean enabled, TechNodeDef root) {
        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        if (enabled) {
            rec.flags.add(flag);
        } else {
            rec.flags.remove(flag);
        }
        save();
    }

    public synchronized int getPaidAmount(String uuid, String nodeId, String itemName) {
        PlayerRecord rec = records.get(uuid);
        if (rec == null) return 0;
        ObjectIntMap<String> map = rec.paidItems.get(nodeId);
        return map == null ? 0 : map.get(itemName, 0);
    }

    public synchronized void addPaidAmount(String uuid, String playerName, String nodeId, String itemName, int delta, TechNodeDef root) {
        if (delta <= 0) return;
        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        ObjectIntMap<String> map = rec.paidItems.get(nodeId);
        if (map == null) {
            map = new ObjectIntMap<>();
            rec.paidItems.put(nodeId, map);
        }
        map.put(itemName, map.get(itemName, 0) + delta);
        save();
    }

    public synchronized void unlockWithParents(String uuid, String playerName, TechNodeDef node, TechNodeDef root) {
        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        TechNodeDef cur = node;
        while (cur != null) {
            rec.unlocked.add(cur.id);
            rec.paidItems.remove(cur.id);
            cur = cur.parent;
        }
        save();
    }

    public synchronized void lockSubtree(String uuid, String playerName, TechNodeDef node, TechNodeDef root) {
        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        lockRecursive(rec, node);
        seedAlwaysUnlocked(rec, root);
        save();
    }

    private void lockRecursive(PlayerRecord rec, TechNodeDef node) {
        if (node == null) return;
        if (!node.alwaysUnlocked && !node.virtual) {
            rec.unlocked.remove(node.id);
            rec.paidItems.remove(node.id);
        }
        for (TechNodeDef child : node.children) {
            lockRecursive(rec, child);
        }
    }

    public synchronized void unlockAll(String uuid, String playerName, TechNodeDef root) {
        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        unlockAllRecursive(rec, root);
        save();
    }

    private void unlockAllRecursive(PlayerRecord rec, TechNodeDef node) {
        if (node == null) return;
        rec.unlocked.add(node.id);
        rec.paidItems.remove(node.id);
        for (TechNodeDef ch : node.children) unlockAllRecursive(rec, ch);
    }

    public synchronized void resetPlayer(String uuid, String playerName, TechNodeDef root) {
        PlayerRecord rec = new PlayerRecord();
        rec.lastKnownName = playerName == null ? "" : playerName;
        seedAlwaysUnlocked(rec, root);
        records.put(uuid, rec);
        save();
    }

    public synchronized void resetAllPlayers(TechNodeDef root) {
        for (var entry : records.entries()) {
            PlayerRecord rec = entry.value;
            rec.unlocked.clear();
            rec.paidItems.clear();
            rec.flags.clear();
            seedAlwaysUnlocked(rec, root);
        }
        save();
    }

    public synchronized String findUuidByNameOrUuid(String query) {
        if (query == null || query.isEmpty()) return null;
        if (records.containsKey(query)) return query;
        String lower = arc.util.Strings.stripColors(query).toLowerCase();
        for (var entry : records.entries()) {
            if (entry.value.lastKnownName != null) {
                String plain = arc.util.Strings.stripColors(entry.value.lastKnownName).toLowerCase();
                if (plain.equals(lower)) {
                    return entry.key;
                }
            }
        }
        for (var entry : records.entries()) {
            if (entry.value.lastKnownName != null) {
                String plain = arc.util.Strings.stripColors(entry.value.lastKnownName).toLowerCase();
                if (plain.contains(lower)) {
                    return entry.key;
                }
            }
        }
        return null;
    }

    public synchronized int playerCount() {
        return records.size;
    }

    public synchronized void load(TechNodeDef root) {
        records.clear();
        if (!saveFile.exists()) return;
        try {
            String raw = saveFile.readString("UTF-8");
            if (raw.trim().isEmpty()) return;
            String json = Jval.read(raw).toString(Jval.Jformat.plain);
            JsonValue doc = new JsonReader().parse(json);
            JsonValue playersVal = doc.get("players");
            if (playersVal == null) return;

            for (JsonValue p = playersVal.child; p != null; p = p.next) {
                String uuid = p.name;
                PlayerRecord rec = new PlayerRecord();
                rec.lastKnownName = p.getString("name", "");

                JsonValue unl = p.get("unlocked");
                if (unl != null) {
                    for (JsonValue u = unl.child; u != null; u = u.next) {
                        rec.unlocked.add(u.asString());
                    }
                }

                JsonValue flg = p.get("flags");
                if (flg != null) {
                    for (JsonValue f = flg.child; f != null; f = f.next) {
                        rec.flags.add(f.asString());
                    }
                }

                JsonValue paid = p.get("paid");
                if (paid != null) {
                    for (JsonValue nodeEntry = paid.child; nodeEntry != null; nodeEntry = nodeEntry.next) {
                        ObjectIntMap<String> itemMap = new ObjectIntMap<>();
                        for (JsonValue itemEntry = nodeEntry.child; itemEntry != null; itemEntry = itemEntry.next) {
                            int amt = itemEntry.asInt();
                            if (amt > 0) itemMap.put(itemEntry.name, amt);
                        }
                        if (!itemMap.isEmpty()) rec.paidItems.put(nodeEntry.name, itemMap);
                    }
                }

                // Migrate legacy 7-branch placeholder records cleanly so only Dagger starts researched
                if (rec.unlocked.contains("serpulo-tier1-root")) {
                    rec.unlocked.clear();
                    rec.paidItems.clear();
                }

                seedAlwaysUnlocked(rec, root);
                records.put(uuid, rec);
            }
        } catch (Exception e) {
            Log.err("[CoolPlugin] Failed to read techtree-progress.hjson", e);
        }
    }

    public synchronized void save() {
        try {
            saveFile.parent().mkdirs();
            StringBuilder sb = new StringBuilder(4096);
            sb.append("# Persistent per-player Tech Tree progress (auto-generated)\n");
            sb.append("players: {\n");
            for (var entry : records.entries()) {
                String uuid = entry.key;
                PlayerRecord rec = entry.value;
                sb.append("  \"").append(escape(uuid)).append("\": {\n");
                sb.append("    name: \"").append(escape(rec.lastKnownName)).append("\"\n");

                sb.append("    unlocked: [\n");
                for (String id : rec.unlocked) {
                    sb.append("      \"").append(escape(id)).append("\"\n");
                }
                sb.append("    ]\n");

                if (!rec.flags.isEmpty()) {
                    sb.append("    flags: [\n");
                    for (String flg : rec.flags) {
                        sb.append("      \"").append(escape(flg)).append("\"\n");
                    }
                    sb.append("    ]\n");
                }

                if (!rec.paidItems.isEmpty()) {
                    sb.append("    paid: {\n");
                    for (var pe : rec.paidItems.entries()) {
                        if (pe.value.isEmpty()) continue;
                        sb.append("      \"").append(escape(pe.key)).append("\": {\n");
                        for (var ie : pe.value.entries()) {
                            sb.append("        \"").append(escape(ie.key)).append("\": ").append(ie.value).append("\n");
                        }
                        sb.append("      }\n");
                    }
                    sb.append("    }\n");
                }

                sb.append("  }\n");
            }
            sb.append("}\n");
            saveFile.writeString(sb.toString(), false, "UTF-8");
        } catch (Exception e) {
            Log.err("[CoolPlugin] Failed to save techtree-progress.hjson", e);
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
