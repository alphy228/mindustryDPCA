package coolplugin.techtree;

import arc.util.CommandHandler;
import arc.util.Log;
import mindustry.gen.Groups;
import mindustry.gen.Player;

/**
 * Registers client (`/tech`, `/techadmin`) and server console (`techtree`) commands
 * for opening, inspecting, unlocking, locking, resetting, and reloading {@code techtree.hjson}.
 */
public final class TechTreeCommands {

    private TechTreeCommands() {}

    public static void registerClientCommands(CommandHandler handler, TechTreeService service) {
        for (String cmd : new String[]{"t", "tree", "tech"}) {
            handler.<Player>register(
                cmd,
                "",
                "Open the Tech Tree.",
                (args, player) -> service.getMenu().open(player)
            );
        }

        handler.<Player>register(
            "techadmin",
            "<reload|regen|unlock|lock|unlockall|reset|flag> [player] [target]",
            "(Admin) Manage server techtree.hjson and per-player unlocks.",
            (args, admin) -> {
                if (!admin.admin) {
                    admin.sendMessage("[scarlet]Admin permission required.[]");
                    return;
                }
                String sub = args[0].toLowerCase();
                switch (sub) {
                    case "reload" -> {
                        service.reloadTree();
                        refreshAllOnline(service);
                        admin.sendMessage("[stat][TechTree][] Reloaded " + service.getTreeFile().path()
                            + " (" + service.totalUnlockableCount() + " nodes).");
                    }
                    case "regen" -> {
                        service.regenerateDefaultTree(false);
                        refreshAllOnline(service);
                        admin.sendMessage("[stat][TechTree][] Regenerated default pre-Thorium/Plastanium tree at "
                            + service.getTreeFile().path() + ".");
                    }
                    case "unlock" -> {
                        if (args.length < 3) {
                            admin.sendMessage("[scarlet]Usage: /techadmin unlock <player> <nodeId> | all[]");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        if (tp == null) {
                            admin.sendMessage("[scarlet]Player '" + args[1] + "' not found.[]");
                            return;
                        }
                        if ("all".equalsIgnoreCase(args[2])) {
                            service.getProgressStore().unlockAll(tp.uuid, tp.name, service.getRoot());
                            if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                            admin.sendMessage("[stat][TechTree][] Unlocked all nodes for " + tp.name + ".");
                            return;
                        }
                        TechNodeDef node = service.findNode(args[2]);
                        if (node == null) {
                            admin.sendMessage("[scarlet]Unknown node '" + args[2] + "'.[]");
                            return;
                        }
                        service.getProgressStore().unlockWithParents(tp.uuid, tp.name, node, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        admin.sendMessage("[stat][TechTree][] Unlocked " + node.resolvedName() + " for " + tp.name + ".");
                    }
                    case "unlockall" -> {
                        TargetPlayer tp = args.length >= 2 ? resolveTargetPlayer(service, args[1]) : new TargetPlayer(admin.uuid(), admin.plainName(), admin);
                        if (tp == null) {
                            admin.sendMessage("[scarlet]Player '" + args[1] + "' not found.[]");
                            return;
                        }
                        service.getProgressStore().unlockAll(tp.uuid, tp.name, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        admin.sendMessage("[stat][TechTree][] Unlocked entire tree for " + tp.name + ".");
                    }
                    case "lock" -> {
                        if (args.length < 3) {
                            admin.sendMessage("[scarlet]Usage: /techadmin lock <player> <nodeId>[]");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        TechNodeDef node = service.findNode(args[2]);
                        if (tp == null || node == null) {
                            admin.sendMessage("[scarlet]Invalid player or node.[]");
                            return;
                        }
                        service.getProgressStore().lockSubtree(tp.uuid, tp.name, node, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        admin.sendMessage("[accent][TechTree][] Locked " + node.resolvedName() + " (+ children) for " + tp.name + ".");
                    }
                    case "reset" -> {
                        TargetPlayer tp = args.length >= 2 ? resolveTargetPlayer(service, args[1]) : new TargetPlayer(admin.uuid(), admin.plainName(), admin);
                        if (tp == null) {
                            admin.sendMessage("[scarlet]Player '" + args[1] + "' not found.[]");
                            return;
                        }
                        service.getProgressStore().resetPlayer(tp.uuid, tp.name, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        admin.sendMessage("[accent][TechTree][] Reset tech progress for " + tp.name + ".");
                    }
                    case "flag" -> {
                        if (args.length < 3) {
                            admin.sendMessage("[scarlet]Usage: /techadmin flag <player> <flagName>[]");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        if (tp == null) {
                            admin.sendMessage("[scarlet]Player '" + args[1] + "' not found.[]");
                            return;
                        }
                        boolean next = !service.getProgressStore().hasFlag(tp.uuid, args[2]);
                        service.getProgressStore().setFlag(tp.uuid, tp.name, args[2], next, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        admin.sendMessage("[stat][TechTree][] Flag '" + args[2] + "' = " + next + " for " + tp.name + ".");
                    }
                    default -> admin.sendMessage("[scarlet]Unknown subcommand. Try reload, regen, unlock, lock, unlockall, reset, flag.[]");
                }
            }
        );
    }

    public static void registerServerCommands(CommandHandler handler, TechTreeService service) {
        handler.register(
            "techtree",
            "<status|reload|regen|unlock|lock|reset|flag> [player] [target]",
            "Manage config/alphystuff/techtree.hjson and persistent per-player tech unlocks.",
            args -> {
                String sub = args[0].toLowerCase();
                switch (sub) {
                    case "status" -> Log.info("[TechTree] Nodes: @, Saved players: @, File: @",
                        service.totalUnlockableCount(),
                        service.getProgressStore().playerCount(),
                        service.getTreeFile().path());
                    case "reload" -> {
                        service.reloadTree();
                        refreshAllOnline(service);
                        Log.info("[TechTree] Reloaded @ (@ unlockable nodes).",
                            service.getTreeFile().path(), service.totalUnlockableCount());
                    }
                    case "regen" -> {
                        service.regenerateDefaultTree(false);
                        refreshAllOnline(service);
                        Log.info("[TechTree] Regenerated default pre-Thorium/Plastanium tree at @.",
                            service.getTreeFile().path());
                    }
                    case "unlock" -> {
                        if (args.length < 3) {
                            Log.warn("Usage: techtree unlock <playerNameOrUuid> <nodeId|all>");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        if (tp == null) {
                            Log.warn("[TechTree] Player '@' not found.", args[1]);
                            return;
                        }
                        if ("all".equalsIgnoreCase(args[2])) {
                            service.getProgressStore().unlockAll(tp.uuid, tp.name, service.getRoot());
                            if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                            Log.info("[TechTree] Unlocked all nodes for @.", tp.name);
                            return;
                        }
                        TechNodeDef node = service.findNode(args[2]);
                        if (node == null) {
                            Log.warn("[TechTree] Unknown node '@'.", args[2]);
                            return;
                        }
                        service.getProgressStore().unlockWithParents(tp.uuid, tp.name, node, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        Log.info("[TechTree] Unlocked '@' for @.", node.id, tp.name);
                    }
                    case "lock" -> {
                        if (args.length < 3) {
                            Log.warn("Usage: techtree lock <playerNameOrUuid> <nodeId>");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        TechNodeDef node = service.findNode(args[2]);
                        if (tp == null || node == null) {
                            Log.warn("[TechTree] Player or node not found.");
                            return;
                        }
                        service.getProgressStore().lockSubtree(tp.uuid, tp.name, node, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        Log.info("[TechTree] Locked '@' (+ children) for @.", node.id, tp.name);
                    }
                    case "reset" -> {
                        if (args.length < 2) {
                            Log.warn("Usage: techtree reset <playerNameOrUuid|all>");
                            return;
                        }
                        if ("all".equalsIgnoreCase(args[1])) {
                            service.getProgressStore().resetAllPlayers(service.getRoot());
                            refreshAllOnline(service);
                            Log.info("[TechTree] Reset tech progress for all saved players.");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        if (tp == null) {
                            Log.warn("[TechTree] Player '@' not found.", args[1]);
                            return;
                        }
                        service.getProgressStore().resetPlayer(tp.uuid, tp.name, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        Log.info("[TechTree] Reset tech progress for @.", tp.name);
                    }
                    case "flag" -> {
                        if (args.length < 3) {
                            Log.warn("Usage: techtree flag <playerNameOrUuid> <flagName>");
                            return;
                        }
                        TargetPlayer tp = resolveTargetPlayer(service, args[1]);
                        if (tp == null) {
                            Log.warn("[TechTree] Player '@' not found.", args[1]);
                            return;
                        }
                        boolean next = !service.getProgressStore().hasFlag(tp.uuid, args[2]);
                        service.getProgressStore().setFlag(tp.uuid, tp.name, args[2], next, service.getRoot());
                        if (tp.online != null) service.getMenu().refreshOpenSession(tp.online);
                        Log.info("[TechTree] Flag '@' = @ for @.", args[2], next, tp.name);
                    }
                    default -> Log.warn("[TechTree] Unknown subcommand '@'. Use status, reload, regen, unlock, lock, reset, flag.", sub);
                }
            }
        );
    }

    private static void refreshAllOnline(TechTreeService service) {
        for (Player p : Groups.player) {
            service.ensurePlayerInitialized(p);
            service.getMenu().refreshOpenSession(p);
        }
    }

    private record TargetPlayer(String uuid, String name, Player online) {}

    private static TargetPlayer resolveTargetPlayer(TechTreeService service, String query) {
        if (query == null || query.isEmpty()) return null;
        String qLower = arc.util.Strings.stripColors(query).toLowerCase();
        for (Player p : Groups.player) {
            String plain = arc.util.Strings.stripColors(p.name == null ? "" : p.name);
            if (p.uuid().equals(query) || plain.toLowerCase().equals(qLower) || plain.toLowerCase().contains(qLower)) {
                return new TargetPlayer(p.uuid(), plain, p);
            }
        }
        String savedUuid = service.getProgressStore().findUuidByNameOrUuid(query);
        if (savedUuid != null) {
            return new TargetPlayer(savedUuid, query, null);
        }
        return null;
    }
}
