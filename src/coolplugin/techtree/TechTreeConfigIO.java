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
 * Loads and writes {@code config/alphystuff/techtree.hjson}, keeping all building/unit item costs,
 * tree structure, and special unlock conditions outside the main plugin class.
 */
public final class TechTreeConfigIO {

    private TechTreeConfigIO() {}

    public static TechNodeDef loadOrCreate(Fi file, UnlockCondition.ConditionRegistry conditionRegistry) {
        if (!file.exists()) {
            TechNodeDef def = DefaultSerpuloPreThoriumTree.createDefaultRoot();
            save(file, def);
            Log.info("[CoolPlugin] Created default pre-Thorium/Plastanium tech tree at @", file.path());
            return def;
        }
        return load(file, conditionRegistry);
    }

    public static TechNodeDef load(Fi file, UnlockCondition.ConditionRegistry conditionRegistry) {
        String rawHjson = file.readString("UTF-8");
        String jsonText = Jval.read(rawHjson).toString(Jval.Jformat.plain);
        JsonValue rootVal = new JsonReader().parse(jsonText);

        JsonValue treeVal = rootVal.has("root") ? rootVal.get("root") : rootVal;
        TechNodeDef root = parseNode(treeVal, conditionRegistry);
        DefaultSerpuloPreThoriumTree.assignParents(root, null);
        return root;
    }

    private static TechNodeDef parseNode(JsonValue val, UnlockCondition.ConditionRegistry conditionRegistry) {
        TechNodeDef node = new TechNodeDef();
        node.virtual = val.getBoolean("virtual", false);
        node.contentType = val.getString("type", node.virtual ? "virtual" : "block");
        node.contentName = val.getString("content", val.getString("id", ""));
        node.id = val.getString("id", node.contentName);
        node.displayName = val.getString("displayName", null);
        node.description = val.getString("description", null);
        node.icon = val.getString("icon", null);
        node.alwaysUnlocked = val.getBoolean("alwaysUnlocked", false);

        if (!node.virtual) {
            node.content = resolveContent(node.contentType, node.contentName);
            if (node.content == null) {
                Log.warn("[CoolPlugin] techtree.hjson node '@' could not resolve content @:@",
                    node.id, node.contentType, node.contentName);
            }
        }

        // Parse explicit item research costs: supports either object { copper: 100, lead: 50 } or array [ "copper/100" ]
        JsonValue costsVal = val.get("costs");
        if (costsVal != null) {
            node.costs = parseCosts(costsVal);
        } else {
            node.costs = ItemStack.empty;
        }

        // Parse special unlock conditions (empty by default, ready for future rules)
        JsonValue condsVal = val.get("conditions");
        if (condsVal != null) {
            for (JsonValue c = condsVal.child; c != null; c = c.next) {
                UnlockCondition parsed = conditionRegistry.parse(c);
                if (parsed != null) node.conditions.add(parsed);
            }
        }

        JsonValue childrenVal = val.get("children");
        if (childrenVal != null) {
            for (JsonValue ch = childrenVal.child; ch != null; ch = ch.next) {
                node.children.add(parseNode(ch, conditionRegistry));
            }
        }

        return node;
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
        try {
            ContentType ct = ContentType.valueOf(typeName);
            if (Vars.content.getByName(ct, contentName) instanceof UnlockableContent uc) {
                return uc;
            }
        } catch (IllegalArgumentException ignored) {
        }
        // Fallback lookup across standard unlockable types
        for (ContentType ct : new ContentType[]{ContentType.block, ContentType.unit, ContentType.item, ContentType.liquid}) {
            if (Vars.content.getByName(ct, contentName) instanceof UnlockableContent uc) {
                return uc;
            }
        }
        return null;
    }

    public static void save(Fi file, TechNodeDef root) {
        file.parent().mkdirs();
        StringBuilder sb = new StringBuilder(16384);
        sb.append("# Server Tech Tree Configuration (config/alphystuff/techtree.hjson)\n");
        sb.append("# - Starts with vanilla Serpulo Tier 1 units unlocked at the root.\n");
        sb.append("# - Contains vanilla pre-Thorium / pre-Plastanium units & buildings with vanilla research costs.\n");
        sb.append("# - Optional per-node `conditions: [ { type: flag, flag: \"...\", description: \"...\" } ]` supported.\n\n");
        sb.append("root: ");
        writeNodeHjson(sb, root, "");
        file.writeString(sb.toString(), false, "UTF-8");
    }

    private static void writeNodeHjson(StringBuilder sb, TechNodeDef node, String indent) {
        String inner = indent + "  ";
        sb.append("{\n");
        sb.append(inner).append("id: ").append(quoteIfNeeded(node.id)).append("\n");
        sb.append(inner).append("type: ").append(quoteIfNeeded(node.contentType)).append("\n");
        if (!node.virtual && node.contentName != null && !node.contentName.equals(node.id)) {
            sb.append(inner).append("content: ").append(quoteIfNeeded(node.contentName)).append("\n");
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

        if (node.costs == null || node.costs.length == 0) {
            sb.append(inner).append("costs: {}\n");
        } else {
            sb.append(inner).append("costs: {\n");
            for (ItemStack s : node.costs) {
                sb.append(inner).append("  ").append(quoteIfNeeded(s.item.name)).append(": ").append(s.amount).append("\n");
            }
            sb.append(inner).append("}\n");
        }

        if (node.conditions.isEmpty()) {
            sb.append(inner).append("conditions: []\n");
        } else {
            sb.append(inner).append("conditions: [\n");
            for (UnlockCondition cond : node.conditions) {
                cond.writeHjson(sb, inner + "  ");
            }
            sb.append(inner).append("]\n");
        }

        if (!node.children.isEmpty()) {
            sb.append(inner).append("children: [\n");
            for (TechNodeDef child : node.children) {
                sb.append(inner).append("  ");
                writeNodeHjson(sb, child, inner + "  ");
            }
            sb.append(inner).append("]\n");
        }

        sb.append(indent).append("}\n");
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
