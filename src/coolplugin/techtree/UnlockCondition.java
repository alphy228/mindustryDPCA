package coolplugin.techtree;

import arc.func.Func;
import arc.struct.ObjectMap;
import arc.util.Strings;
import arc.util.serialization.JsonValue;
import mindustry.gen.Player;

/**
 * Pluggable unlock condition system for {@code techtree.hjson} nodes.
 * Each node in HJSON may list {@code conditions: [ { type: "...", ... } ]}.
 * Currently no nodes define special conditions by default, while custom condition types
 * can be registered dynamically via {@link ConditionRegistry#register(String, Func)}.
 */
public interface UnlockCondition {

    /** Condition type key as written in {@code techtree.hjson}. */
    String type();

    /** Human-readable requirement description shown in the info panel (like vanilla Objectives). */
    String describe(Player player, TechProgressStore progress);

    /** Returns true if {@code player} currently satisfies this condition. */
    boolean isMet(Player player, TechProgressStore progress);

    /** Serializes this condition back into HJSON/JSON representation if needed. */
    default void writeHjson(StringBuilder out, String indent) {
        out.append(indent).append("{ type: ").append(type()).append(" }\n");
    }

    final class ConditionRegistry {
        private final ObjectMap<String, Func<JsonValue, UnlockCondition>> factories = new ObjectMap<>();

        public ConditionRegistry() {
            // Built-in node unlock dependency condition: { type: research, node: "router" }
            register("research", json -> {
                String targetNode = json.getString("node", "");
                String label = json.getString("description", "");
                return new NodeUnlockedCondition(targetNode, label);
            });

            // Built-in admin-granted flag condition: { type: flag, flag: "boss_defeated", description: "Defeat Boss" }
            register("flag", json -> {
                String flag = json.getString("flag", "");
                String label = json.getString("description", "Requires special unlock: " + flag);
                return new FlagCondition(flag, label);
            });
        }

        public void register(String type, Func<JsonValue, UnlockCondition> factory) {
            factories.put(type, factory);
        }

        public UnlockCondition parse(JsonValue json) {
            if (json == null) return null;
            if (json.isString()) {
                return new FlagCondition(json.asString(), "Requires condition: " + json.asString());
            }
            String type = json.getString("type", "flag");
            Func<JsonValue, UnlockCondition> factory = factories.get(type);
            if (factory != null) {
                return factory.get(json);
            }
            String desc = json.getString("description", "Requires condition '" + type + "'");
            return new CustomUnregisteredCondition(type, desc);
        }
    }

    final class NodeUnlockedCondition implements UnlockCondition {
        public final String nodeId;
        public final String customLabel;

        public NodeUnlockedCondition(String nodeId, String customLabel) {
            this.nodeId = nodeId;
            this.customLabel = customLabel;
        }

        @Override
        public String type() {
            return "research";
        }

        @Override
        public String describe(Player player, TechProgressStore progress) {
            if (customLabel != null && !customLabel.isEmpty()) return customLabel;
            return "Research " + Strings.capitalize(nodeId.replace('-', ' '));
        }

        @Override
        public boolean isMet(Player player, TechProgressStore progress) {
            return player != null && progress.isUnlocked(player.uuid(), nodeId);
        }

        @Override
        public void writeHjson(StringBuilder out, String indent) {
            out.append(indent).append("{ type: research, node: ").append(nodeId).append(" }\n");
        }
    }

    final class FlagCondition implements UnlockCondition {
        public final String flag;
        public final String description;

        public FlagCondition(String flag, String description) {
            this.flag = flag;
            this.description = description;
        }

        @Override
        public String type() {
            return "flag";
        }

        @Override
        public String describe(Player player, TechProgressStore progress) {
            return description;
        }

        @Override
        public boolean isMet(Player player, TechProgressStore progress) {
            return player != null && progress.hasFlag(player.uuid(), flag);
        }

        @Override
        public void writeHjson(StringBuilder out, String indent) {
            out.append(indent).append("{ type: flag, flag: ").append(flag)
               .append(", description: \"").append(description.replace("\"", "\\\"")).append("\" }\n");
        }
    }

    final class CustomUnregisteredCondition implements UnlockCondition {
        public final String conditionType;
        public final String description;

        public CustomUnregisteredCondition(String conditionType, String description) {
            this.conditionType = conditionType;
            this.description = description;
        }

        @Override
        public String type() {
            return conditionType;
        }

        @Override
        public String describe(Player player, TechProgressStore progress) {
            return description;
        }

        @Override
        public boolean isMet(Player player, TechProgressStore progress) {
            return player != null && progress.hasFlag(player.uuid(), conditionType);
        }
    }
}
