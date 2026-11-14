package coolplugin.techtree;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;
import mindustry.type.Item;
import mindustry.type.ItemStack;

/**
 * Loads and writes {@code config/alphystuff/techtree.hjson} in compact, clean HJSON.
 */
public final class TechTreeConfigIO {

    private static final ContentType[] DATABASE_SEARCH_ORDER = {
        ContentType.item,
        ContentType.liquid,
        ContentType.block,
        ContentType.unit,
        ContentType.sector,
        ContentType.status,
        ContentType.planet
    };

    public static final class LoadedConfig {
        public final TechNodeDef root;
        public final Seq<String> whitelist;

        public LoadedConfig(TechNodeDef root, Seq<String> whitelist) {
            this.root = root;
            this.whitelist = whitelist;
        }
    }

    private TechTreeConfigIO() {}

    public static LoadedConfig loadConfigOrCreate(Fi file, UnlockCondition.ConditionRegistry conditionRegistry) {
        if (!file.exists()) {
            TechNodeDef def = DefaultSerpuloPreThoriumTree.createDefaultRoot();
            Seq<String> whitelist = new Seq<>();
            save(file, def, whitelist);
            Log.info("[CoolPlugin] Created default pre-Thorium/Plastanium tech tree at @", file.path());
            return new LoadedConfig(def, whitelist);
        }
        return loadConfig(file, conditionRegistry);
    }

    public static TechNodeDef loadOrCreate(Fi file, UnlockCondition.ConditionRegistry conditionRegistry) {
        return loadConfigOrCreate(file, conditionRegistry).root;
    }

    public static LoadedConfig loadConfig(Fi file, UnlockCondition.ConditionRegistry conditionRegistry) {
        String rawHjson = file.readString("UTF-8");
        String jsonText = Jval.read(rawHjson).toString(Jval.Jformat.plain);
        JsonValue rootVal = new JsonReader().parse(jsonText);

        Seq<String> whitelist = new Seq<>();
        JsonValue wlVal = rootVal.get("whitelist");
        if (wlVal != null && wlVal.isArray()) {
            for (JsonValue entry = wlVal.child; entry != null; entry = entry.next) {
                String name = entry.asString();
                if (name != null) {
                    String trimmed = name.trim();
                    if (!trimmed.isEmpty() && !whitelist.contains(trimmed)) {
                        whitelist.add(trimmed);
                    }
                }
            }
        }

        JsonValue treeVal = rootVal.has("root") ? rootVal.get("root") : rootVal;
        TechNodeDef root = parseNode(treeVal, conditionRegistry, TechNodeDef.DIR_DOWN);
        DefaultSerpuloPreThoriumTree.assignParents(root, null);
        return new LoadedConfig(root, whitelist);
    }

    public static TechNodeDef load(Fi file, UnlockCondition.ConditionRegistry conditionRegistry) {
        return loadConfig(file, conditionRegistry).root;
    }

    private static TechNodeDef parseNode(JsonValue val, UnlockCondition.ConditionRegistry conditionRegistry, String inheritedDir) {
        TechNodeDef node = new TechNodeDef();
        node.virtual = val.getBoolean("virtual", false);

        String resourceShorthand = val.getString("resource", val.getString("database", null));
        String defaultType = node.virtual ? "virtual" : (resourceShorthand != null ? "resource" : "block");
        node.contentType = val.getString("type", defaultType);

        String fallbackContent = resourceShorthand != null ? resourceShorthand : val.getString("id", "");
        node.contentName = val.getString("content", fallbackContent);
        node.id = val.getString("id", node.contentName);
        node.direction = val.getString("direction", inheritedDir);
        node.displayName = val.getString("displayName", null);
        node.description = val.getString("description", null);
        node.icon = val.getString("icon", null);
        node.alwaysUnlocked = val.getBoolean("alwaysUnlocked", false);
        node.pluginUnlocked = val.getBoolean("pluginUnlocked",
            val.getBoolean("unlockedByPlugin", val.getBoolean("manualUnlock", false)));

        if (!node.virtual && !"shop-item".equals(node.contentType)) {
            node.content = resolveContent(node.contentType, node.contentName);
            if (node.content != null && ("resource".equalsIgnoreCase(node.contentType) || "database".equalsIgnoreCase(node.contentType))) {
                node.contentType = node.content.getContentType().name();
            } else if (node.content == null && !node.contentName.startsWith("dp-")) {
                Log.warn("[CoolPlugin] techtree.hjson node '@' could not resolve content @:@ (will retry after DataPatch load)",
                    node.id, node.contentType, node.contentName);
            }
        }

        JsonValue costsVal = val.get("costs");
        node.costs = costsVal != null ? parseCosts(costsVal) : ItemStack.empty;

        JsonValue condsVal = val.get("conditions");
        if (condsVal != null) {
            for (JsonValue c = condsVal.child; c != null; c = c.next) {
                UnlockCondition parsed = conditionRegistry.parse(c);
                if (parsed != null) node.conditions.add(parsed);
            }
        }

        // Children of left/right T1 nodes default to "down" unless explicitly overridden; "up" branches inherit "up"
        String defaultChildDir = TechNodeDef.DIR_UP.equals(node.direction) ? TechNodeDef.DIR_UP : TechNodeDef.DIR_DOWN;
        JsonValue childrenVal = val.get("children");
        if (childrenVal != null) {
            for (JsonValue ch = childrenVal.child; ch != null; ch = ch.next) {
                node.children.add(parseNode(ch, conditionRegistry, defaultChildDir));
            }
        }

        return node;
    }

    /** Re-attempts database entry lookup for any nodes referencing DataPatch (`dp-*`) content loaded on map start. */
    public static boolean rebindContentRecursive(TechNodeDef node) {
        if (node == null) return false;
        boolean changed = false;
        if (!node.virtual && !"shop-item".equals(node.contentType) && node.contentName != null && !node.contentName.isEmpty()) {
            UnlockableContent resolved = resolveContent(node.contentType, node.contentName);
            if (resolved != null && resolved != node.content) {
                node.content = resolved;
                if ("resource".equalsIgnoreCase(node.contentType) || "database".equalsIgnoreCase(node.contentType)) {
                    node.contentType = node.content.getContentType().name();
                }
                changed = true;
            }
        }
        for (TechNodeDef child : node.children) {
            if (rebindContentRecursive(child)) changed = true;
        }
        return changed;
    }

    private static ItemStack[] parseCosts(JsonValue costsVal) {
        Seq<ItemStack> list = new Seq<>();
        if (costsVal.isObject()) {
            for (JsonValue entry = costsVal.child; entry != null; entry = entry.next) {
                Item item = Vars.content.item(entry.name);
                int amount = entry.asInt();
                if (item != null && amount > 0) {
                    list.add(new ItemStack(item, amount));
                } else if (item == null) {
                    Log.warn("[CoolPlugin] Unknown item '@' in techtree.hjson costs", entry.name);
                }
            }
        } else if (costsVal.isArray()) {
            for (JsonValue entry = costsVal.child; entry != null; entry = entry.next) {
                if (entry.isString()) {
                    String[] parts = entry.asString().split("[/:]");
                    if (parts.length == 2) {
                        Item item = Vars.content.item(parts[0].trim());
                        int amount = Integer.parseInt(parts[1].trim());
                        if (item != null && amount > 0) list.add(new ItemStack(item, amount));
                    }
                } else if (entry.isObject()) {
                    Item item = Vars.content.item(entry.getString("item", ""));
                    int amount = entry.getInt("amount", 0);
                    if (item != null && amount > 0) list.add(new ItemStack(item, amount));
                }
            }
        }
        return list.toArray(ItemStack.class);
    }

    public static UnlockableContent resolveContent(String typeName, String contentName) {
        if (Vars.content == null || contentName == null || contentName.isEmpty()) return null;

        UnlockableContent direct = resolveContentExact(typeName, contentName);
        if (direct != null) return direct;

        if (!contentName.startsWith("dp-")) {
            return resolveContentExact(typeName, "dp-" + contentName);
        }
        return null;
    }

    private static UnlockableContent resolveContentExact(String typeName, String contentName) {
        if (typeName != null && !"resource".equalsIgnoreCase(typeName) && !"database".equalsIgnoreCase(typeName)) {
            try {
                ContentType ct = ContentType.valueOf(typeName);
                if (Vars.content.getByName(ct, contentName) instanceof UnlockableContent uc) {
                    return uc;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (ContentType ct : DATABASE_SEARCH_ORDER) {
            if (Vars.content.getByName(ct, contentName) instanceof UnlockableContent uc) {
                return uc;
            }
        }
        return null;
    }

    public static void save(Fi file, TechNodeDef root) {
        save(file, root, new Seq<>());
    }

    public static void save(Fi file, TechNodeDef root, Seq<String> whitelist) {
        file.parent().mkdirs();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# Server Tech Tree (config/alphystuff/techtree.hjson)\n");
        sb.append("# Optional per-node fields: direction (up|down|left|right), conditions: [{ type: flag, flag: \"...\", description: \"...\" }]\n");
        sb.append("# Custom plugin-unlocked database nodes: { id: \"titanium\", type: \"item\", content: \"titanium\", pluginUnlocked: true }\n");
        sb.append("# Buildings are available without research; map bans still apply.\n\n");

        sb.append("whitelist: [");
        if (whitelist != null && !whitelist.isEmpty()) {
            for (int i = 0; i < whitelist.size; i++) {
                if (i > 0) sb.append(", ");
                sb.append("\"").append(escape(whitelist.get(i))).append("\"");
            }
        }
        sb.append("]\n\n");

        sb.append("root: ");
        writeNodeHjson(sb, root, "", TechNodeDef.DIR_DOWN);
        file.writeString(sb.toString(), false, "UTF-8");
    }

    private static void writeNodeHjson(StringBuilder sb, TechNodeDef node, String indent, String inheritedDir) {
        boolean sameContent = node.contentName == null || node.contentName.isEmpty() || node.contentName.equals(node.id);
        boolean isLeafSimple = node.children.isEmpty()
            && node.conditions.isEmpty()
            && !node.alwaysUnlocked
            && !node.virtual
            && sameContent
            && node.displayName == null
            && node.description == null
            && node.icon == null
            && (node.direction == null || node.direction.equals(inheritedDir));

        if (isLeafSimple) {
            sb.append("{ id: \"").append(escape(node.id)).append("\"");
            if (!"block".equals(node.contentType)) {
                sb.append(", type: \"").append(escape(node.contentType)).append("\"");
            }
            if (node.pluginUnlocked) {
                sb.append(", pluginUnlocked: true");
                if (node.costs != null && node.costs.length > 0) {
                    sb.append(", costs: ").append(formatInlineCosts(node.costs));
                }
            } else {
                sb.append(", costs: ").append(formatInlineCosts(node.costs));
            }
            sb.append(" }\n");
            return;
        }

        String inner = indent + "  ";
        sb.append("{\n");
        sb.append(inner).append("id: ").append(quoteIfNeeded(node.id)).append("\n");
        if (!"block".equals(node.contentType)) {
            sb.append(inner).append("type: ").append(quoteIfNeeded(node.contentType)).append("\n");
        }
        if (!node.virtual && node.contentName != null && !node.contentName.isEmpty() && !node.contentName.equals(node.id)) {
            sb.append(inner).append("content: ").append(quoteIfNeeded(node.contentName)).append("\n");
        }
        if (node.direction != null && !node.direction.equals(inheritedDir)) {
            sb.append(inner).append("direction: ").append(quoteIfNeeded(node.direction)).append("\n");
        }
        if (node.virtual) {
            sb.append(inner).append("virtual: true\n");
        }
        if (node.displayName != null && !node.displayName.isEmpty()) {
            sb.append(inner).append("displayName: \"").append(escape(node.displayName)).append("\"\n");
        }
        if (node.description != null && !node.description.isEmpty()) {
            sb.append(inner).append("description: \"").append(escape(node.description)).append("\"\n");
        }
        if (node.icon != null && !node.icon.isEmpty()) {
            sb.append(inner).append("icon: ").append(quoteIfNeeded(node.icon)).append("\n");
        }
        if (node.alwaysUnlocked) {
            sb.append(inner).append("alwaysUnlocked: true\n");
        }
        if (node.pluginUnlocked) {
            sb.append(inner).append("pluginUnlocked: true\n");
        }

        if (!node.pluginUnlocked || (node.costs != null && node.costs.length > 0)) {
            sb.append(inner).append("costs: ").append(formatInlineCosts(node.costs)).append("\n");
        }

        if (!node.conditions.isEmpty()) {
            sb.append(inner).append("conditions: [\n");
            for (UnlockCondition cond : node.conditions) {
                cond.writeHjson(sb, inner + "  ");
            }
            sb.append(inner).append("]\n");
        }

        if (!node.children.isEmpty()) {
            String childDefaultDir = TechNodeDef.DIR_UP.equals(node.direction) ? TechNodeDef.DIR_UP : TechNodeDef.DIR_DOWN;
            sb.append(inner).append("children: [\n");
            for (TechNodeDef child : node.children) {
                sb.append(inner).append("  ");
                writeNodeHjson(sb, child, inner + "  ", childDefaultDir);
            }
            sb.append(inner).append("]\n");
        }

        sb.append(indent).append("}\n");
    }

    private static String formatInlineCosts(ItemStack[] costs) {
        if (costs == null || costs.length == 0) return "{}";
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < costs.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(quoteIfNeeded(costs[i].item.name)).append(": ").append(costs[i].amount);
        }
        sb.append(" }");
        return sb.toString();
    }

    private static String quoteIfNeeded(String s) {
        if (s == null || s.isEmpty()) return "\"\"";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
                return "\"" + escape(s) + "\"";
            }
        }
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
