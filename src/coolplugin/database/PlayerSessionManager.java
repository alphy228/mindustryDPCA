package coolplugin.database;

import arc.Events;
import arc.func.Cons2;
import arc.struct.ObjectMap;
import arc.util.Nullable;
import coolplugin.gameplay.FactionDef;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.type.UnitType;

import java.util.Locale;

/**
 * Tracks each connected player's active {@link AccountRecord} session (starting as a
 * UUID-bound Guest on join, and upgrading or switching on {@code /register}, {@code /login}, {@code /logout})
 * along with their currently active faction ID for per-faction unit & Tech Tree persistence.
 */
public class PlayerSessionManager {

    public static final String DEFAULT_FACTION_ID = "sharded";

    private final PlayerDatabase database;
    private final ObjectMap<String, AccountRecord> sessionsByUuid = new ObjectMap<>();
    private final ObjectMap<String, String> activeFactionByUuid = new ObjectMap<>();
    private @Nullable Cons2<Player, AccountRecord> accountSwitchListener;
    private boolean started = false;

    public PlayerSessionManager(PlayerDatabase database) {
        this.database = database;
    }

    public PlayerDatabase getDatabase() {
        return database;
    }

    public void setAccountSwitchListener(@Nullable Cons2<Player, AccountRecord> listener) {
        this.accountSwitchListener = listener;
    }

    public void start() {
        if (started) return;
        started = true;
        database.init();

        Events.on(PlayerJoin.class, e -> {
            if (e.player != null) {
                getOrInitSession(e.player);
            }
        });

        Events.on(PlayerLeave.class, e -> {
            if (e.player != null) {
                String uuid = e.player.uuid();
                sessionsByUuid.remove(uuid);
                activeFactionByUuid.remove(uuid);
            }
        });
    }

    public synchronized AccountRecord getOrInitSession(Player player) {
        String uuid = player.uuid();
        AccountRecord existing = sessionsByUuid.get(uuid);
        if (existing != null) return existing;

        AccountRecord guest = database.getOrCreateGuestByUuid(uuid, player.plainName());
        sessionsByUuid.put(uuid, guest);
        return guest;
    }

    public synchronized AccountRecord resolveOrCreateAccountByUuid(String playerUuid, @Nullable String playerName) {
        AccountRecord active = sessionsByUuid.get(playerUuid);
        if (active != null) return active;

        // Also support synthetic admin lookup tokens "acct:<id>" when offline registered accounts are managed
        if (playerUuid != null && playerUuid.startsWith("acct:")) {
            try {
                long id = Long.parseLong(playerUuid.substring("acct:".length()));
                AccountRecord byId = database.getById(id);
                if (byId != null) return byId;
            } catch (NumberFormatException ignored) {
            }
        }

        AccountRecord guest = database.getOrCreateGuestByUuid(playerUuid, playerName);
        if (Groups.player != null && Groups.player.contains(p -> p != null && playerUuid.equals(p.uuid()))) {
            sessionsByUuid.put(playerUuid, guest);
        }
        return guest;
    }

    public synchronized @Nullable AccountRecord getActiveSession(String playerUuid) {
        return sessionsByUuid.get(playerUuid);
    }

    public synchronized void setActiveFaction(String playerUuid, @Nullable FactionDef faction) {
        if (playerUuid == null) return;
        if (faction == null) {
            activeFactionByUuid.remove(playerUuid);
        } else {
            activeFactionByUuid.put(playerUuid, faction.id.toLowerCase(Locale.ROOT));
        }
    }

    public synchronized void setActiveFactionId(String playerUuid, @Nullable String factionId) {
        if (playerUuid == null) return;
        if (factionId == null || factionId.isBlank()) {
            activeFactionByUuid.remove(playerUuid);
        } else {
            activeFactionByUuid.put(playerUuid, factionId.trim().toLowerCase(Locale.ROOT));
        }
    }

    public synchronized String getActiveFactionId(@Nullable String playerUuid) {
        if (playerUuid == null) return DEFAULT_FACTION_ID;
        if(Groups.player!=null)for(Player player:Groups.player)if(playerUuid.equals(player.uuid())){
            for(FactionDef faction:FactionDef.values())if(player.team()==faction.team()){
                activeFactionByUuid.put(playerUuid,faction.id);return faction.id;
            }
        }
        String active = activeFactionByUuid.get(playerUuid);
        return (active == null || active.isEmpty()) ? DEFAULT_FACTION_ID : active;
    }

    public synchronized @Nullable String findTargetKeyForQuery(String query) {
        if (query == null || query.isBlank()) return null;
        String trimmed = query.trim();

        // 1. Check online players by UUID, active login, or display name first
        for (ObjectMap.Entry<String, AccountRecord> e : sessionsByUuid) {
            if (trimmed.equals(e.key)) return e.key;
            if (e.value != null && e.value.login != null && e.value.login.equalsIgnoreCase(trimmed)) {
                return e.key;
            }
            if (e.value != null && e.value.lastName != null && e.value.lastName.equalsIgnoreCase(trimmed)) {
                return e.key;
            }
        }

        // 2. Check database by login, UUID, or last_name
        AccountRecord dbAcc = database.findByLoginOrUuid(trimmed);
        if (dbAcc != null) {
            for (ObjectMap.Entry<String, AccountRecord> e : sessionsByUuid) {
                if (e.value != null && e.value.id == dbAcc.id) {
                    return e.key;
                }
            }
            return dbAcc.uuid != null ? dbAcc.uuid : ("acct:" + dbAcc.id);
        }
        return null;
    }

    /**
     * Converts the player's current Guest account into a {@code login + password} account in-place.
     */
    public synchronized PlayerDatabase.AccountResult registerCurrentGuest(
        Player player,
        String login,
        String password
    ) {
        AccountRecord current = getOrInitSession(player);
        if (current.isRegistered()) {
            return new PlayerDatabase.AccountResult(
                PlayerDatabase.Status.ALREADY_REGISTERED,
                current,
                "You are already logged in as '" + current.login + "'."
            );
        }

        PlayerDatabase.AccountResult res = database.registerGuestAccount(
            current.id,
            login,
            password,
            player.plainName()
        );
        if (res.status == PlayerDatabase.Status.OK && res.account != null) {
            sessionsByUuid.put(player.uuid(), res.account);
        }
        return res;
    }

    /**
     * Authenticates {@code login + password} and switches the player's active session to that account.
     */
    public synchronized PlayerDatabase.AccountResult login(
        Player player,
        String login,
        String password
    ) {
        PlayerDatabase.AccountResult res = database.authenticate(login, password, player.plainName());
        if (res.status != PlayerDatabase.Status.OK || res.account == null) {
            return res;
        }

        AccountRecord current = sessionsByUuid.get(player.uuid());
        if (current != null && current.id == res.account.id) {
            return new PlayerDatabase.AccountResult(
                PlayerDatabase.Status.OK,
                res.account,
                "Already logged in as '" + res.account.login + "'."
            );
        }

        if (isAccountOnlineElsewhere(res.account.id, player.uuid())) {
            return new PlayerDatabase.AccountResult(
                PlayerDatabase.Status.ALREADY_REGISTERED,
                null,
                "Account '" + res.account.login + "' is already logged in on another connection."
            );
        }

        sessionsByUuid.put(player.uuid(), res.account);
        if (accountSwitchListener != null) {
            accountSwitchListener.get(player, res.account);
        }
        return res;
    }

    /**
     * Logs out of a registered account and reverts the player's session to a UUID-bound Guest account.
     */
    public synchronized PlayerDatabase.AccountResult logout(Player player) {
        AccountRecord current = getOrInitSession(player);
        if (current.isGuest()) {
            return new PlayerDatabase.AccountResult(
                PlayerDatabase.Status.NOT_FOUND,
                current,
                "You are currently on a guest session (not logged into an account)."
            );
        }

        AccountRecord guest = database.getOrCreateGuestByUuid(player.uuid(), player.plainName());
        sessionsByUuid.put(player.uuid(), guest);
        activeFactionByUuid.remove(player.uuid());
        if (accountSwitchListener != null) {
            accountSwitchListener.get(player, guest);
        }
        return new PlayerDatabase.AccountResult(
            PlayerDatabase.Status.OK,
            guest,
            "Logged out. Now playing on a guest session."
        );
    }

    public synchronized PlayerDatabase.AccountResult changePassword(
        Player player,
        String oldPassword,
        String newPassword
    ) {
        AccountRecord current = getOrInitSession(player);
        if (current.isGuest()) {
            return new PlayerDatabase.AccountResult(
                PlayerDatabase.Status.NOT_FOUND,
                current,
                "Register an account first with /register <login> <password>."
            );
        }
        PlayerDatabase.AccountResult res = database.changePassword(current.id, oldPassword, newPassword);
        if (res.status == PlayerDatabase.Status.OK && res.account != null) {
            sessionsByUuid.put(player.uuid(), res.account);
        }
        return res;
    }

    /**
     * Returns the saved {@link UnitType} for {@code (player, faction)}, or {@code null} if the
     * player has never started as {@code faction}.
     */
    public synchronized @Nullable UnitType resolveSavedUnitTypeOrNull(Player player, FactionDef faction) {
        AccountRecord acc = getOrInitSession(player);
        String savedName = database.getFactionUnitType(acc.id, faction.id, null);
        if (savedName == null || savedName.isEmpty()) {
            return null;
        }
        return lookupUnitTypeOrDefault(savedName);
    }

    public synchronized UnitType resolveSavedUnitType(Player player, FactionDef faction) {
        AccountRecord acc = getOrInitSession(player);
        String savedName = database.getFactionUnitType(acc.id, faction.id, UnitTypes.dagger.name);
        return lookupUnitTypeOrDefault(savedName);
    }

    public synchronized void saveUnitType(Player player, @Nullable FactionDef faction, @Nullable UnitType unitType) {
        if (player == null || faction == null || unitType == null) return;
        AccountRecord acc = getOrInitSession(player);
        database.setFactionUnitType(acc.id, faction.id, unitType.name);
    }

    public synchronized void saveUnitTypeByUuid(String playerUuid, @Nullable FactionDef faction, @Nullable UnitType unitType) {
        if (playerUuid == null || faction == null || unitType == null) return;
        AccountRecord acc = resolveOrCreateAccountByUuid(playerUuid, null);
        database.setFactionUnitType(acc.id, faction.id, unitType.name);
    }

    public synchronized @Nullable AccountRecord resolveTargetAccount(String query) {
        String key = findTargetKeyForQuery(query);
        if (key == null) return null;
        return resolveOrCreateAccountByUuid(key, null);
    }

    public synchronized String resolveTargetFactionId(String query, @Nullable String explicitFaction) {
        if (explicitFaction != null && !explicitFaction.isBlank()) {
            return explicitFaction.trim().toLowerCase(Locale.ROOT);
        }
        String key = findTargetKeyForQuery(query);
        return getActiveFactionId(key);
    }

    public static UnitType lookupUnitTypeOrDefault(@Nullable String name) {
        if (name != null && !name.isEmpty() && Vars.content != null) {
            UnitType direct = Vars.content.unit(name);
            if(direct==null && name.startsWith("dp-paint-")){
                String base=coolplugin.gameplay.ColouringAssets.baseName(name);
                if(!base.equals(name)){
                    String oldScheme=name.substring("dp-paint-".length(),name.length()-base.length()-1);
                    String shared=coolplugin.gameplay.ColouringAssets.paletteAliases.get(oldScheme);
                    var palette=coolplugin.gameplay.ColouringAssets.palette(shared);
                    if(palette!=null)direct=Vars.content.unit(coolplugin.gameplay.ColouringAssets.variant(base,palette));
                    if(direct==null)direct=Vars.content.unit(base);
                }
            }
            if (direct != null) return direct;
            if (!name.startsWith("dp-")) {
                UnitType dp = Vars.content.unit("dp-" + name);
                if (dp != null) return dp;
            }
        }
        return UnitTypes.dagger;
    }

    private boolean isAccountOnlineElsewhere(long accountId, String currentUuid) {
        for (ObjectMap.Entry<String, AccountRecord> e : sessionsByUuid) {
            if (!currentUuid.equals(e.key) && e.value != null && e.value.id == accountId) {
                if (Groups.player != null
                    && Groups.player.contains(p -> p != null && p.con != null && p.con.isConnected() && e.key.equals(p.uuid()))) {
                    return true;
                }
            }
        }
        return false;
    }
}
