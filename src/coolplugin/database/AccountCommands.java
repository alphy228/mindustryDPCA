package coolplugin.database;

import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.Log;
import coolplugin.gameplay.FactionDef;
import mindustry.Vars;
import mindustry.gen.Player;
import mindustry.type.UnitType;

/**
 * Registers client commands ({@code /register}, {@code /login}, {@code /logout}, {@code /passwd}, {@code /account})
 * and server console commands ({@code account status|info|setpass|setunit}).
 */
public final class AccountCommands {

    private AccountCommands() {}

    public static void registerClientCommands(CommandHandler handler, PlayerSessionManager sessions) {
        handler.<Player>register(
            "register",
            "<login> <password>",
            "Secure your guest profile with a login + password (detaches it from your UUID).",
            (args, player) -> {
                PlayerDatabase.AccountResult res = sessions.registerCurrentGuest(player, args[0], args[1]);
                String col = res.status == PlayerDatabase.Status.OK ? "[lime]" : "[scarlet]";
                player.sendMessage(col + res.message + "[]");
            }
        );

        handler.<Player>register(
            "login",
            "<login> <password>",
            "Log into your password-protected account.",
            (args, player) -> {
                PlayerDatabase.AccountResult res = sessions.login(player, args[0], args[1]);
                String col = res.status == PlayerDatabase.Status.OK ? "[lime]" : "[scarlet]";
                player.sendMessage(col + res.message + "[]");
            }
        );

        handler.<Player>register(
            "logout",
            "Log out of your registered account back to a guest session.",
            (args, player) -> {
                PlayerDatabase.AccountResult res = sessions.logout(player);
                String col = res.status == PlayerDatabase.Status.OK ? "[accent]" : "[scarlet]";
                player.sendMessage(col + res.message + "[]");
            }
        );

        handler.<Player>register(
            "passwd",
            "<oldPassword> <newPassword>",
            "Change the password on your registered account.",
            (args, player) -> {
                PlayerDatabase.AccountResult res = sessions.changePassword(player, args[0], args[1]);
                String col = res.status == PlayerDatabase.Status.OK ? "[lime]" : "[scarlet]";
                player.sendMessage(col + res.message + "[]");
            }
        );

        handler.<Player>register(
            "account",
            "Show your current account status and saved per-faction units.",
            (args, player) -> {
                AccountRecord acc = sessions.getOrInitSession(player);
                PlayerDatabase db = sessions.getDatabase();

                StringBuilder sb = new StringBuilder();
                if (acc.isRegistered()) {
                    sb.append("[accent]Account:[] [lime]").append(acc.login).append("[] [gray](ID #").append(acc.id).append(", password-secured)[]\n");
                } else {
                    sb.append("[accent]Account:[] [lightgray]Guest[] [gray](ID #").append(acc.id)
                      .append(", UUID-bound -- use [white]/register <login> <password>[gray] to secure)[]\n");
                }

                for (FactionDef faction : FactionDef.values()) {
                    if (!faction.available) continue;
                    String unitName = db.getFactionUnitType(acc.id, faction.id, null);
                    int unlockedCount = db.getFactionTechProgress(acc.id, faction.id).unlocked.size;
                    if (unitName == null) {
                        sb.append("  [#").append(faction.accentHex).append("]").append(faction.title)
                          .append("[gray]: [white]New game[]\n");
                    } else {
                        sb.append("  [#").append(faction.accentHex).append("]").append(faction.title)
                          .append("[gray]: unit=[white]").append(unitName)
                          .append("[gray], tech=[white]").append(unlockedCount).append(" unlocked[]\n");
                    }
                }
                player.sendMessage(sb.toString().trim());
            }
        );
    }

    public static void registerServerCommands(CommandHandler handler, PlayerSessionManager sessions) {
        PlayerDatabase db = sessions.getDatabase();

        handler.register(
            "account",
            "<status|info|setpass|setunit> [arg1] [arg2] [arg3]",
            "Inspect or manage SQLite player accounts (config/alphystuff/players.db).",
            args -> {
                String sub = args[0].toLowerCase();
                switch (sub) {
                    case "status" -> {
                        PlayerDatabase.Stats s = db.getStats();
                        Log.info("[AccountDB] total=@ (registered=@, guests=@, factionRows=@, techRows=@) file=@",
                            s.totalAccounts(), s.registeredAccounts(), s.guestAccounts(),
                            s.factionRows(), s.techProgressRows(), db.getDbFile().path());
                    }
                    case "info" -> {
                        if (args.length < 2) {
                            Log.warn("Usage: account info <loginOrUuid>");
                            return;
                        }
                        AccountRecord acc = db.findByLoginOrUuid(args[1]);
                        if (acc == null) {
                            Log.warn("[AccountDB] Account '@' not found.", args[1]);
                            return;
                        }
                        Log.info("[AccountDB] id=#@ type=@ login=@ uuid=@ lastName=@",
                            acc.id, acc.isRegistered() ? "REGISTERED" : "GUEST", acc.login, acc.uuid, acc.lastName);
                        Seq<FactionProfileRecord> profiles = db.getFactionProfiles(acc.id);
                        if (profiles.isEmpty()) {
                            Log.info("  (no saved faction rows; unit=null until started)");
                        } else {
                            for (FactionProfileRecord p : profiles) {
                                Log.info("  faction=@ unit=@ extra=@",
                                    p.factionId, p.unitTypeName != null ? p.unitTypeName : "null", p.extraJson);
                            }
                        }
                    }
                    case "setpass" -> {
                        if (args.length < 3) {
                            Log.warn("Usage: account setpass <login> <newPassword>");
                            return;
                        }
                        PlayerDatabase.AccountResult res = db.adminSetPassword(args[1], args[2]);
                        Log.info("[AccountDB] @", res.message);
                    }
                    case "setunit" -> {
                        if (args.length < 4) {
                            Log.warn("Usage: account setunit <loginOrUuid> <sharded|crux> <unitName>");
                            return;
                        }
                        AccountRecord acc = db.findByLoginOrUuid(args[1]);
                        if (acc == null) {
                            Log.warn("[AccountDB] Account '@' not found.", args[1]);
                            return;
                        }
                        FactionDef faction = FactionDef.byId(args[2]);
                        if (faction == null) {
                            Log.warn("[AccountDB] Unknown faction '@'.", args[2]);
                            return;
                        }
                        UnitType resolved = Vars.content == null ? null : (Vars.content.unit(args[3]) != null ? Vars.content.unit(args[3]) : Vars.content.unit("dp-" + args[3]));
                        if(resolved==null){Log.warn("[AccountDB] Unknown unit '@'.",args[3]);return;}
                        String unitName = resolved.name;
                        db.setFactionUnitType(acc.id, faction.id, unitName);
                        for(Player online:mindustry.gen.Groups.player){
                            AccountRecord active=sessions.getActiveSession(online.uuid());
                            if(active!=null&&active.id==acc.id)coolplugin.gameplay.PlayerUnitSystem.applyAccountUnitNow(online,faction,resolved);
                        }
                        Log.info("[AccountDB] Set account #@ (@) faction '@' unit -> '@'.",
                            acc.id, acc.isRegistered() ? acc.login : acc.uuid, faction.id, unitName);
                    }
                    default -> Log.warn("Unknown subcommand '@'. Expected: status | info | setpass | setunit", sub);
                }
            }
        );
    }
}
