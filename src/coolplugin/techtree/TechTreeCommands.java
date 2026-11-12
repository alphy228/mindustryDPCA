package coolplugin.techtree;

import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.world.Block;

/**
 * Registers client (`/t`, `/tree`, `/tech`, `/techadmin`) and server console (`techtree`) commands
 * for opening, inspecting, unlocking, locking, resetting, whitelisting, and reloading {@code techtree.hjson}.
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

        // Ensure `/sync` streams per-player bannedBlocks + hideBannedBlocks=true cleanly
        if (Vars.netServer != null) {
            handler.<Player>register("sync", "Re-synchronize world state.", (args, player) -> {
                if (player.isLocal()) {
                    player.sendMessage("[scarlet]Re-synchronizing as the host is pointless.");
                } else {
                    if (Time.timeSinceMillis(player.getInfo().lastSyncTime) < 1000 * 5) {
                        player.sendMessage("[scarlet]You may only /sync every 5 seconds.");
                        return;
                    }
                    player.getInfo().lastSyncTime = Time.millis();
                    Call.worldDataBegin(player.con);
                    service.withPlayerWorldRules(player, () -> Vars.netServer.sendWorldData(player));
                }
            });
        }

        handler.<Player>register(
            "techadmin",
            "<reload|regen|unlock|lock|unlockall|reset|whitelist|flag> [arg1] [arg2]",
            "(Admin) Manage server techtree.hjson, building whitelist, and per-player unlocks.",
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
                            + " (" + service.totalUnlockableCount() + " nodes, "
                            + service.getWhitelist().size + " whitelisted blocks).");
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
                            syncOnlineTarget(service, tp, null);
                            admin.sendMessage("[stat][TechTree][] Unlocked all nodes for " + tp.name + ".");
                            return;
                        }
                        TechNodeDef node = service.findNode(args[2]);
                        if (node == null) {
                            admin.sendMessage("[scarlet]Unknown node '" + args[2] + "'.[]");
                            return;
                        }
                        service.getProgressStore().unlockWithParents(tp.uuid, tp.name, node, service.getRoot());
                        Block unlockedBlk = (node.content instanceof Block b) ? b : null;
                        syncOnlineTarget(service, tp, unlockedBlk);
                        admin.sendMessage("[stat][TechTree][] Unlocked " + node.resolvedName() + " for " + tp.name + ".");
                    }
                    case "unlockall" -> {
                        TargetPlayer tp = args.length >= 2 ? resolveTargetPlayer(service, args[1]) : new TargetPlayer(admin.uuid(), admin.plainName(), admin);
                        if (tp == null) {
                            admin.sendMessage("[scarlet]Player '" + args[1] + "' not found.[]");
                            return;
                        }
                        service.getProgressStore().unlockAll(tp.uuid, tp.name, service.getRoot());
                        syncOnlineTarget(service, tp, null);
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
                        syncOnlineTarget(service, tp, null);
                        admin.sendMessage("[accent][TechTree][] Locked " + node.resolvedName() + " (+ children) for " + tp.name + ".");
                    }
                    case "reset" -> {
                        TargetPlayer tp = args.length >= 2 ? resolveTargetPlayer(service, args[1]) : new TargetPlayer(admin.uuid(), admin.plainName(), admin);
                        if (tp == null) {
                            admin.sendMessage("[scarlet]Player '" + args[1] + "' not found.[]");
                            return;
                        }
                        service.getProgressStore().resetPlayer(tp.uuid, tp.name, service.getRoot());
                        syncOnlineTarget(service, tp, null);
                        admin.sendMessage("[accent][TechTree][] Reset tech progress for " + tp.name + ".");
                    }
                    case "whitelist" -> {
                        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
                        switch (action) {
                            case "list" -> {
                                Seq<String> wl = service.getWhitelist();
                                admin.sendMessage("[stat][TechTree][] Whitelist (" + wl.size + "): "
                                    + (wl.isEmpty() ? "(empty)" : wl.toString(", ")));
                            }
                            case "add" -> {
                                if (args.length < 3) {
                                    admin.sendMessage("[scarlet]Usage: /techadmin whitelist add <blockName>[]");
                                    return;
                                }
                                Block blk = Vars.content == null ? null : Vars.content.block(args[2]);
                                String name = blk != null ? blk.name : args[2].trim();
                                if (service.addWhitelist(name)) {
                                    refreshAllOnline(service);
                                    admin.sendMessage("[stat][TechTree][] Added '" + name + "' to building whitelist.");
                                } else {
                                    admin.sendMessage("[accent][TechTree][] '" + name + "' is already in whitelist.");
                                }
                            }
                            case "remove" -> {
                                if (args.length < 3) {
                                    admin.sendMessage("[scarlet]Usage: /techadmin whitelist remove <blockName>[]");
                                    return;
                                }
                                if (service.removeWhitelist(args[2].trim())) {
                                    refreshAllOnline(service);
                                    admin.sendMessage("[accent][TechTree][] Removed '" + args[2].trim() + "' from building whitelist.");
                                } else {
                                    admin.sendMessage("[scarlet][TechTree][] '" + args[2].trim() + "' was not in whitelist.");
                                }
                            }
                            default -> admin.sendMessage("[scarlet]Usage: /techadmin whitelist <list|add|remove> [blockName][]");
                        }
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
                    default -> admin.sendMessage("[scarlet]Unknown subcommand. Try reload, regen, unlock, lock, unlockall, reset, whitelist, flag.[]");
                }
            }
        );
    }

    public static void registerServerCommands(CommandHandler handler, TechTreeService service) {
        handler.register(
            "techtree",
            "<status|reload|regen|unlock|lock|reset|whitelist|flag> [arg1] [arg2]",
            "Manage config/alphystuff/techtree.hjson, building whitelist, and per-player unlocks.",
            args -> {
                String sub = args[0].toLowerCase();
                switch (sub) {
                    case "status" -> Log.info("[TechTree] Nodes: @, Whitelist: @, Saved players: @, hideBannedBlocks: @, File: @",
                        service.totalUnlockableCount(),
                        service.getWhitelist().size,
                        service.getProgressStore().playerCount(),
                        Vars.state != null && Vars.state.rules != null && Vars.state.rules.hideBannedBlocks,
                        service.getTreeFile().path());
                    case "reload" -> {
                        service.reloadTree();
                        refreshAllOnline(service);
                        Log.info("[TechTree] Reloaded @ (@ unlockable nodes, @ whitelisted blocks).",
                            service.getTreeFile().path(), service.totalUnlockableCount(), service.getWhitelist().size);
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
                            syncOnlineTarget(service, tp, null);
                            Log.info("[TechTree] Unlocked all nodes for @.", tp.name);
                            return;
                        }
                        TechNodeDef node = service.findNode(args[2]);
                        if (node == null) {
                            Log.warn("[TechTree] Unknown node '@'.", args[2]);
                            return;
                        }
                        service.getProgressStore().unlockWithParents(tp.uuid, tp.name, node, service.getRoot());
                        Block unlockedBlk = (node.content instanceof Block b) ? b : null;
                        syncOnlineTarget(service, tp, unlockedBlk);
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
                        syncOnlineTarget(service, tp, null);
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
                        syncOnlineTarget(service, tp, null);
                        Log.info("[TechTree] Reset tech progress for @.", tp.name);
                    }
                    case "whitelist" -> {
                        String action = args.length >= 2 ? args[1].toLowerCase() : "list";
                        switch (action) {
                            case "list" -> Log.info("[TechTree] Whitelist (@): @",
                                service.getWhitelist().size,
                                service.getWhitelist().isEmpty() ? "(empty)" : service.getWhitelist().toString(", "));
                            case "add" -> {
                                if (args.length < 3) {
                                    Log.warn("Usage: techtree whitelist add <blockName>");
                                    return;
                                }
                                Block blk = Vars.content == null ? null : Vars.content.block(args[2]);
                                String name = blk != null ? blk.name : args[2].trim();
                                if (service.addWhitelist(name)) {
                                    refreshAllOnline(service);
                                    Log.info("[TechTree] Added '@' to whitelist.", name);
                                } else {
                                    Log.info("[TechTree] '@' is already in whitelist.", name);
                                }
                            }
                            case "remove" -> {
                                if (args.length < 3) {
                                    Log.warn("Usage: techtree whitelist remove <blockName>");
                                    return;
                                }
                                if (service.removeWhitelist(args[2].trim())) {
                                    refreshAllOnline(service);
                                    Log.info("[TechTree] Removed '@' from whitelist.", args[2].trim());
                                } else {
                                    Log.warn("[TechTree] '@' was not in whitelist.", args[2].trim());
                                }
                            }
                            default -> Log.warn("Usage: techtree whitelist <list|add|remove> [blockName]");
                        }
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
                    default -> Log.warn("[TechTree] Unknown subcommand '@'. Use status, reload, regen, unlock, lock, reset, whitelist, flag.", sub);
                }
            }
        );
    }

    private static void syncOnlineTarget(TechTreeService service, TargetPlayer tp, Block unlockedBlock) {
        if (tp.online != null) {
            service.syncPlayerRulesLive(tp.online, unlockedBlock);
            service.getMenu().refreshOpenSession(tp.online);
        }
    }

    private static void refreshAllOnline(TechTreeService service) {
        if (Groups.player == null) return;
        for (Player p : Groups.player) {
            service.ensurePlayerInitialized(p);
            service.syncPlayerRulesLive(p, null);
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
