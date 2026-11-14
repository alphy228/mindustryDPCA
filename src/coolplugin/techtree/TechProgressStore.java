package coolplugin.techtree;

import arc.files.Fi;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.Jval;
import coolplugin.database.AccountRecord;
import coolplugin.database.PlayerDatabase;
import coolplugin.database.PlayerSessionManager;

import java.util.Locale;

/**
 * Persistent server-side Tech Tree progress store.
 * <p>
 * When constructed with {@link PlayerDatabase} + {@link PlayerSessionManager}, all Tech Tree unlocks,
 * partial item contributions, and condition flags are persisted in SQLite (`players.db`,
 * table {@code account_tech_progress}) keyed per {@code (account_id, faction_id)} — so Sharded and
 * Crux maintain independent Tech Trees per account, and {@code /register}, {@code /login}, and
 * {@code /logout} seamlessly preserve or switch progress.
 */
public class TechProgressStore {

    public static class PlayerRecord {
        public String lastKnownName = "";
        public final ObjectSet<String> unlocked = new ObjectSet<>();
        public final ObjectSet<String> flags = new ObjectSet<>();
        public final ObjectMap<String, ObjectIntMap<String>> paidItems = new ObjectMap<>();
    }

    private record ProfileKey(long accountId, String factionId) {
        String cacheKey() {
            return accountId + ":" + factionId;
        }
    }

    private final @Nullable Fi saveFile;
    private final @Nullable PlayerDatabase database;
    private final @Nullable PlayerSessionManager sessionManager;

    /** Legacy file-mode map (`uuid -> PlayerRecord`). */
    private final ObjectMap<String, PlayerRecord> records = new ObjectMap<>();
    /** SQLite per-(account, faction) write-through cache (`"accountId:factionId" -> PlayerRecord`). */
    private final ObjectMap<String, PlayerRecord> dbCache = new ObjectMap<>();
    private @Nullable TechNodeDef cachedRoot;

    public TechProgressStore(Fi saveFile) {
        this.saveFile = saveFile;
        this.database = null;
        this.sessionManager = null;
    }

    public TechProgressStore(PlayerDatabase database, PlayerSessionManager sessionManager) {
        this.saveFile = null;
        this.database = database;
        this.sessionManager = sessionManager;
    }

    public synchronized String scope(String uuid){
        ProfileKey profile=resolveProfileKey(uuid,null);return profile==null?uuid:profile.cacheKey();
    }

    public boolean isDatabaseBacked() {
        return database != null && sessionManager != null;
    }

    private @Nullable ProfileKey resolveProfileKey(@Nullable String uuid, @Nullable String playerName) {
        if (!isDatabaseBacked() || uuid == null || uuid.isEmpty()) return null;
        AccountRecord acc = sessionManager.resolveOrCreateAccountByUuid(uuid, playerName);
        String factionId = sessionManager.getActiveFactionId(uuid).toLowerCase(Locale.ROOT);
        return new ProfileKey(acc.id, factionId);
    }

    private PlayerRecord getOrLoadDbRecord(ProfileKey key, @Nullable String playerName, @Nullable TechNodeDef root, boolean persistIfNew) {
        TechNodeDef effectiveRoot = root != null ? root : cachedRoot;
        if (root != null) cachedRoot = root;

        String ck = key.cacheKey();
        PlayerRecord rec = dbCache.get(ck);
        if (rec != null) {
            if (playerName != null && !playerName.isBlank()) {
                rec.lastKnownName = arc.util.Strings.stripColors(playerName).trim();
            }
            if (seedAlwaysUnlocked(rec, effectiveRoot)) {
                persistDbRecord(key, rec);
            }
            return rec;
        }

        PlayerDatabase.TechProgressData loaded = database.getFactionTechProgress(key.accountId, key.factionId);
        rec = new PlayerRecord();
        if (playerName != null && !playerName.isBlank()) {
            rec.lastKnownName = arc.util.Strings.stripColors(playerName).trim();
        }
        rec.unlocked.addAll(loaded.unlocked);
        rec.flags.addAll(loaded.flags);
        for (var e : loaded.paidItems.entries()) {
            ObjectIntMap<String> copy = new ObjectIntMap<>();
            for (var ie : e.value.entries()) {
                copy.put(ie.key, ie.value);
            }
            rec.paidItems.put(e.key, copy);
        }

        boolean seeded = seedAlwaysUnlocked(rec, effectiveRoot);
        dbCache.put(ck, rec);
        if ((persistIfNew && !loaded.existsInDb) || (loaded.existsInDb && seeded)) {
            persistDbRecord(key, rec);
        }
        return rec;
    }

    private void persistDbRecord(ProfileKey key, PlayerRecord rec) {
        if (database == null) return;
        database.saveFactionTechProgress(key.accountId, key.factionId, rec.unlocked, rec.flags, rec.paidItems);
    }

    /** Evicts all in-memory cached faction tech records for {@code accountId} after a {@code wipedata} reset. */
    public synchronized void evictAccountCache(long accountId) {
        String prefix = accountId + ":";
        arc.struct.Seq<String> keys = dbCache.keys().toSeq();
        for (String k : keys) {
            if (k != null && k.startsWith(prefix)) {
                dbCache.remove(k);
            }
        }
    }

    public synchronized PlayerRecord getOrCreate(String uuid, String playerName, TechNodeDef root) {
        if (root != null) cachedRoot = root;
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return new PlayerRecord();
            return getOrLoadDbRecord(key, playerName, root, true);
        }

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

    private boolean seedAlwaysUnlocked(PlayerRecord rec, @Nullable TechNodeDef node) {
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
        this.cachedRoot = root;
        if (isDatabaseBacked()) {
            for (var entry : dbCache.entries()) {
                if (seedAlwaysUnlocked(entry.value, root)) {
                    int sep = entry.key.indexOf(':');
                    if (sep > 0) {
                        long accId = Long.parseLong(entry.key.substring(0, sep));
                        String facId = entry.key.substring(sep + 1);
                        persistDbRecord(new ProfileKey(accId, facId), entry.value);
                    }
                }
            }
            return;
        }

        for (var entry : records.entries()) {
            seedAlwaysUnlocked(entry.value, root);
        }
        save();
    }

    public synchronized boolean isUnlocked(String uuid, TechNodeDef node) {
        if (node == null) return false;
        if (node.alwaysUnlocked || node.virtual) return true;
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, null);
            if (key == null) return false;
            PlayerRecord rec = getOrLoadDbRecord(key, null, cachedRoot, false);
            return rec.unlocked.contains(node.id);
        }
        PlayerRecord rec = records.get(uuid);
        return rec != null && rec.unlocked.contains(node.id);
    }

    public synchronized boolean isUnlocked(String uuid, String nodeId) {
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, null);
            if (key == null) return false;
            PlayerRecord rec = getOrLoadDbRecord(key, null, cachedRoot, false);
            return rec.unlocked.contains(nodeId);
        }
        PlayerRecord rec = records.get(uuid);
        return rec != null && rec.unlocked.contains(nodeId);
    }

    public synchronized boolean isUnlockedForAccountFaction(long accountId, String factionId, String nodeId) {
        if (!isDatabaseBacked()) return false;
        ProfileKey key = new ProfileKey(accountId, factionId.toLowerCase(Locale.ROOT));
        PlayerRecord rec = getOrLoadDbRecord(key, null, cachedRoot, false);
        return rec.unlocked.contains(nodeId);
    }

    public synchronized int countUnlockedForAccountFaction(long accountId, String factionId) {
        if (!isDatabaseBacked()) return 0;
        ProfileKey key = new ProfileKey(accountId, factionId.toLowerCase(Locale.ROOT));
        PlayerRecord rec = getOrLoadDbRecord(key, null, cachedRoot, false);
        return rec.unlocked.size;
    }

    public synchronized boolean hasFlag(String uuid, String flag) {
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, null);
            if (key == null) return false;
            PlayerRecord rec = getOrLoadDbRecord(key, null, cachedRoot, false);
            return rec.flags.contains(flag);
        }
        PlayerRecord rec = records.get(uuid);
        return rec != null && rec.flags.contains(flag);
    }

    public synchronized void setFlag(String uuid, String playerName, String flag, boolean enabled, TechNodeDef root) {
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return;
            PlayerRecord rec = getOrLoadDbRecord(key, playerName, root, true);
            if (enabled) rec.flags.add(flag);
            else rec.flags.remove(flag);
            persistDbRecord(key, rec);
            return;
        }

        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        if (enabled) {
            rec.flags.add(flag);
        } else {
            rec.flags.remove(flag);
        }
        save();
    }

    public synchronized int getPaidAmount(String uuid, String nodeId, String itemName) {
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, null);
            if (key == null) return 0;
            PlayerRecord rec = getOrLoadDbRecord(key, null, cachedRoot, false);
            ObjectIntMap<String> map = rec.paidItems.get(nodeId);
            return map == null ? 0 : map.get(itemName, 0);
        }

        PlayerRecord rec = records.get(uuid);
        if (rec == null) return 0;
        ObjectIntMap<String> map = rec.paidItems.get(nodeId);
        return map == null ? 0 : map.get(itemName, 0);
    }

    public synchronized void addPaidAmount(String uuid, String playerName, String nodeId, String itemName, int delta, TechNodeDef root) {
        if (delta <= 0) return;
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return;
            PlayerRecord rec = getOrLoadDbRecord(key, playerName, root, true);
            ObjectIntMap<String> map = rec.paidItems.get(nodeId);
            if (map == null) {
                map = new ObjectIntMap<>();
                rec.paidItems.put(nodeId, map);
            }
            map.put(itemName, map.get(itemName, 0) + delta);
            persistDbRecord(key, rec);
            return;
        }

        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        ObjectIntMap<String> map = rec.paidItems.get(nodeId);
        if (map == null) {
            map = new ObjectIntMap<>();
            rec.paidItems.put(nodeId, map);
        }
        map.put(itemName, map.get(itemName, 0) + delta);
        save();
    }

    public synchronized boolean unlockSingle(String uuid, String playerName, TechNodeDef node, TechNodeDef root) {
        if (uuid == null || node == null) return false;
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return false;
            PlayerRecord rec = getOrLoadDbRecord(key, playerName, root, true);
            boolean added = rec.unlocked.add(node.id);
            boolean hadPaid = rec.paidItems.remove(node.id) != null;
            if (added || hadPaid) {
                persistDbRecord(key, rec);
            }
            return added;
        }

        PlayerRecord rec = getOrCreate(uuid, playerName, root);
        boolean added = rec.unlocked.add(node.id);
        boolean hadPaid = rec.paidItems.remove(node.id) != null;
        if (added || hadPaid) {
            save();
        }
        return added;
    }

    public synchronized void unlockWithParents(String uuid, String playerName, TechNodeDef node, TechNodeDef root) {
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return;
            PlayerRecord rec = getOrLoadDbRecord(key, playerName, root, true);
            TechNodeDef cur = node;
            while (cur != null) {
                rec.unlocked.add(cur.id);
                rec.paidItems.remove(cur.id);
                cur = cur.parent;
            }
            persistDbRecord(key, rec);
            return;
        }

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
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return;
            PlayerRecord rec = getOrLoadDbRecord(key, playerName, root, true);
            lockRecursive(rec, node);
            seedAlwaysUnlocked(rec, root);
            persistDbRecord(key, rec);
            return;
        }

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
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return;
            PlayerRecord rec = getOrLoadDbRecord(key, playerName, root, true);
            unlockAllRecursive(rec, root);
            persistDbRecord(key, rec);
            return;
        }

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
        if (isDatabaseBacked()) {
            ProfileKey key = resolveProfileKey(uuid, playerName);
            if (key == null) return;
            PlayerRecord rec = new PlayerRecord();
            rec.lastKnownName = playerName == null ? "" : playerName;
            seedAlwaysUnlocked(rec, root);
            dbCache.put(key.cacheKey(), rec);
            persistDbRecord(key, rec);
            return;
        }

        PlayerRecord rec = new PlayerRecord();
        rec.lastKnownName = playerName == null ? "" : playerName;
        seedAlwaysUnlocked(rec, root);
        records.put(uuid, rec);
        save();
    }

    public synchronized void resetAllPlayers(TechNodeDef root) {
        if (isDatabaseBacked()) {
            dbCache.clear();
            database.resetAllTechProgress();
            return;
        }

        for (var entry : records.entries()) {
            PlayerRecord rec = entry.value;
            rec.unlocked.clear();
            rec.paidItems.clear();
            rec.flags.clear();
            seedAlwaysUnlocked(rec, root);
        }
        save();
    }

    public synchronized @Nullable String findUuidByNameOrUuid(String query) {
        if (query == null || query.isEmpty()) return null;
        if (isDatabaseBacked()) {
            return sessionManager.findTargetKeyForQuery(query);
        }
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
        if (isDatabaseBacked()) {
            return database.getStats().techProgressRows();
        }
        return records.size;
    }

    public synchronized void load(TechNodeDef root) {
        this.cachedRoot = root;
        if (isDatabaseBacked()) {
            dbCache.clear();
            database.init();
            return;
        }

        records.clear();
        if (saveFile == null || !saveFile.exists()) return;
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
                        for (JsonValue itemEntry = paid.child; itemEntry != null; itemEntry = itemEntry.next) {
                            // handled below
                        }
                        for (JsonValue itemEntry = nodeEntry.child; itemEntry != null; itemEntry = itemEntry.next) {
                            int amt = itemEntry.asInt();
                            if (amt > 0) itemMap.put(itemEntry.name, amt);
                        }
                        if (!itemMap.isEmpty()) rec.paidItems.put(nodeEntry.name, itemMap);
                    }
                }

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
        if (isDatabaseBacked() || saveFile == null) return;
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
