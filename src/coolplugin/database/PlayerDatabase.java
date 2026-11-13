package coolplugin.database;

import arc.files.Fi;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import arc.util.serialization.JsonReader;
import arc.util.serialization.JsonValue;
import org.sqlite.SQLiteConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Embedded SQLite store (`config/alphystuff/players.db`) managing:
 * <ul>
 *   <li>UUID-backed Guest accounts (`uuid != NULL, login == NULL`).</li>
 *   <li>Secured accounts (`uuid == NULL, login != NULL`) converted in-place from Guest accounts
 *       when {@link #registerGuestAccount} is called, detaching the account from UUID spoofing.</li>
 *   <li>Per-faction persistent {@code unit_type} (`account_factions`) and per-faction Tech Tree
 *       progress (`account_tech_progress`).</li>
 * </ul>
 */
public class PlayerDatabase implements AutoCloseable {

    public static final Pattern VALID_LOGIN = Pattern.compile("^[A-Za-z0-9_.-]{3,24}$");
    public static final int MIN_PASSWORD_LENGTH = 4;
    public static final int MAX_PASSWORD_LENGTH = 128;

    public enum Status {
        OK,
        INVALID_LOGIN_FORMAT,
        INVALID_PASSWORD_LENGTH,
        ALREADY_REGISTERED,
        LOGIN_TAKEN,
        INVALID_CREDENTIALS,
        NOT_FOUND,
        DB_ERROR
    }

    public static final class AccountResult {
        public final Status status;
        public final @Nullable AccountRecord account;
        public final String message;

        public AccountResult(Status status, @Nullable AccountRecord account, String message) {
            this.status = status;
            this.account = account;
            this.message = message;
        }
    }

    public static final class TechProgressData {
        public final ObjectSet<String> unlocked = new ObjectSet<>();
        public final ObjectSet<String> flags = new ObjectSet<>();
        public final ObjectMap<String, ObjectIntMap<String>> paidItems = new ObjectMap<>();
        public final boolean existsInDb;

        public TechProgressData(boolean existsInDb) {
            this.existsInDb = existsInDb;
        }
    }

    public static final class EquipmentData {
        /** Ordered list of equipped item IDs (max 10; indices 0..3 grant passive bonuses, 4..9 apply -10% speed each). */
        public final Seq<String> equipped = new Seq<>();
        /** Unequipped owned item counts per item ID. */
        public final ObjectIntMap<String> stash = new ObjectIntMap<>();
    }

    public record Stats(
        int totalAccounts,
        int registeredAccounts,
        int guestAccounts,
        int factionRows,
        int techProgressRows
    ) {}

    private final Fi dbFile;
    private @Nullable Connection connection;

    public PlayerDatabase(Fi dbFile) {
        this.dbFile = dbFile;
    }

    public Fi getDbFile() {
        return dbFile;
    }

    public synchronized void init() {
        if (connection != null) return;
        try {
            if (dbFile.parent() != null) {
                dbFile.parent().mkdirs();
            }

            SQLiteConfig cfg = new SQLiteConfig();
            cfg.enforceForeignKeys(true);
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
            cfg.setBusyTimeout(5000);

            String jdbcUrl = "jdbc:sqlite:" + dbFile.file().getAbsolutePath();
            // Connect via SQLiteConfig directly to avoid DriverManager ModClassLoader visibility issues
            this.connection = cfg.createConnection(jdbcUrl);

            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS accounts (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid          TEXT UNIQUE,
                        login         TEXT,
                        login_lower   TEXT UNIQUE,
                        password_hash TEXT,
                        password_salt TEXT,
                        last_name     TEXT,
                        created_at    INTEGER NOT NULL,
                        updated_at    INTEGER NOT NULL
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_factions (
                        account_id    INTEGER NOT NULL,
                        faction_id    TEXT NOT NULL,
                        unit_type     TEXT DEFAULT NULL,
                        extra_json    TEXT NOT NULL DEFAULT '{}',
                        updated_at    INTEGER NOT NULL,
                        PRIMARY KEY (account_id, faction_id),
                        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_tech_progress (
                        account_id    INTEGER NOT NULL,
                        faction_id    TEXT NOT NULL,
                        unlocked_json TEXT NOT NULL DEFAULT '[]',
                        paid_json     TEXT NOT NULL DEFAULT '{}',
                        flags_json    TEXT NOT NULL DEFAULT '[]',
                        updated_at    INTEGER NOT NULL,
                        PRIMARY KEY (account_id, faction_id),
                        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_orbital_storage (
                        account_id    INTEGER NOT NULL,
                        faction_id    TEXT NOT NULL,
                        item_name     TEXT NOT NULL,
                        amount        INTEGER NOT NULL DEFAULT 0,
                        updated_at    INTEGER NOT NULL,
                        PRIMARY KEY (account_id, faction_id, item_name),
                        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                    )
                    """);

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS account_equipped_items (
                        account_id    INTEGER NOT NULL,
                        faction_id    TEXT NOT NULL,
                        equipped_json TEXT NOT NULL DEFAULT '[]',
                        stash_json    TEXT NOT NULL DEFAULT '{}',
                        updated_at    INTEGER NOT NULL,
                        PRIMARY KEY (account_id, faction_id),
                        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
                    )
                    """);

                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_uuid ON accounts(uuid)");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_accounts_login_lower ON accounts(login_lower)");
            }

            Stats stats = getStats();
            Log.info("[CoolPlugin] SQLite PlayerDatabase ready (@ accounts: @ registered, @ guests, @ tech rows, file=@)",
                stats.totalAccounts(), stats.registeredAccounts(), stats.guestAccounts(),
                stats.techProgressRows(), dbFile.path());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize SQLite PlayerDatabase at " + dbFile.path(), e);
        }
    }

    /**
     * Loads the Guest account bound to {@code uuid}, or creates a new Guest account if none exists
     * (including when a previous Guest account on {@code uuid} was upgraded to {@code login + password}).
     */
    public synchronized AccountRecord getOrCreateGuestByUuid(String uuid, @Nullable String playerName) {
        ensureOpen();
        long now = System.currentTimeMillis();
        try {
            try (PreparedStatement sel = connection.prepareStatement(
                "SELECT id, uuid, login, last_name, created_at, updated_at FROM accounts WHERE uuid = ? AND login IS NULL"
            )) {
                sel.setString(1, uuid);
                try (ResultSet rs = sel.executeQuery()) {
                    if (rs.next()) {
                        long id = rs.getLong("id");
                        long createdAt = rs.getLong("created_at");
                        try (PreparedStatement upd = connection.prepareStatement(
                            "UPDATE accounts SET last_name = COALESCE(?, last_name), updated_at = ? WHERE id = ?"
                        )) {
                            upd.setString(1, playerName);
                            upd.setLong(2, now);
                            upd.setLong(3, id);
                            upd.executeUpdate();
                        }
                        return new AccountRecord(
                            id,
                            rs.getString("uuid"),
                            rs.getString("login"),
                            playerName != null ? playerName : rs.getString("last_name"),
                            createdAt,
                            now
                        );
                    }
                }
            }

            try (PreparedStatement ins = connection.prepareStatement(
                "INSERT INTO accounts (uuid, login, login_lower, password_hash, password_salt, last_name, created_at, updated_at) "
                    + "VALUES (?, NULL, NULL, NULL, NULL, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            )) {
                ins.setString(1, uuid);
                ins.setString(2, playerName);
                ins.setLong(3, now);
                ins.setLong(4, now);
                ins.executeUpdate();
                try (ResultSet keys = ins.getGeneratedKeys()) {
                    if (keys.next()) {
                        long newId = keys.getLong(1);
                        return new AccountRecord(newId, uuid, null, playerName, now, now);
                    }
                }
            }
            throw new SQLException("Failed to retrieve generated account ID for guest " + uuid);
        } catch (SQLException e) {
            throw new RuntimeException("SQLite error in getOrCreateGuestByUuid(" + uuid + ")", e);
        }
    }

    /**
     * Converts an existing UUID-bound Guest account in-place into a password-protected account:
     * sets {@code uuid = NULL} and stores {@code login} + salted PBKDF2 hash, preserving all
     * {@code account_factions} and {@code account_tech_progress} rows while permanently detaching
     * the account from UUID theft.
     */
    public synchronized AccountResult registerGuestAccount(
        long guestAccountId,
        String rawLogin,
        String rawPassword,
        @Nullable String playerName
    ) {
        ensureOpen();
        String login = rawLogin == null ? "" : rawLogin.trim();
        if (!VALID_LOGIN.matcher(login).matches()) {
            return new AccountResult(
                Status.INVALID_LOGIN_FORMAT,
                null,
                "Login must be 3-24 characters using letters, numbers, '_', '-', or '.'."
            );
        }
        if (rawPassword == null
            || rawPassword.length() < MIN_PASSWORD_LENGTH
            || rawPassword.length() > MAX_PASSWORD_LENGTH) {
            return new AccountResult(
                Status.INVALID_PASSWORD_LENGTH,
                null,
                "Password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters."
            );
        }

        String loginLower = login.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        try {
            AccountRecord existing = getByIdInternal(guestAccountId);
            if (existing == null) {
                return new AccountResult(Status.NOT_FOUND, null, "Guest session account not found.");
            }
            if (existing.isRegistered()) {
                return new AccountResult(
                    Status.ALREADY_REGISTERED,
                    existing,
                    "You are already logged into registered account '" + existing.login + "'."
                );
            }

            try (PreparedStatement chk = connection.prepareStatement(
                "SELECT id FROM accounts WHERE login_lower = ?"
            )) {
                chk.setString(1, loginLower);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) {
                        return new AccountResult(
                            Status.LOGIN_TAKEN,
                            null,
                            "Login '" + login + "' is already taken."
                        );
                    }
                }
            }

            PasswordHasher.HashedPassword hp = PasswordHasher.hashPassword(rawPassword);

            try (PreparedStatement upd = connection.prepareStatement(
                "UPDATE accounts SET uuid = NULL, login = ?, login_lower = ?, password_hash = ?, password_salt = ?, "
                    + "last_name = COALESCE(?, last_name), updated_at = ? WHERE id = ? AND login IS NULL"
            )) {
                upd.setString(1, login);
                upd.setString(2, loginLower);
                upd.setString(3, hp.hashBase64());
                upd.setString(4, hp.saltBase64());
                upd.setString(5, playerName);
                upd.setLong(6, now);
                upd.setLong(7, guestAccountId);
                int changed = upd.executeUpdate();
                if (changed != 1) {
                    return new AccountResult(Status.DB_ERROR, null, "Could not convert guest account.");
                }
            }

            AccountRecord secured = getByIdInternal(guestAccountId);
            return new AccountResult(
                Status.OK,
                secured,
                "Account '" + login + "' registered! Your progress is now tied to login + password instead of UUID."
            );
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in registerGuestAccount", e);
            return new AccountResult(Status.DB_ERROR, null, "Database error while registering account.");
        }
    }

    public synchronized AccountResult authenticate(String rawLogin, String rawPassword, @Nullable String playerName) {
        ensureOpen();
        if (rawLogin == null || rawPassword == null) {
            return new AccountResult(Status.INVALID_CREDENTIALS, null, "Invalid login or password.");
        }
        String loginLower = rawLogin.trim().toLowerCase(Locale.ROOT);
        if (loginLower.isEmpty()) {
            return new AccountResult(Status.INVALID_CREDENTIALS, null, "Invalid login or password.");
        }

        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT id, uuid, login, password_hash, password_salt, last_name, created_at, updated_at "
                + "FROM accounts WHERE login_lower = ?"
        )) {
            ps.setString(1, loginLower);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new AccountResult(Status.INVALID_CREDENTIALS, null, "Invalid login or password.");
                }
                String hash = rs.getString("password_hash");
                String salt = rs.getString("password_salt");
                if (!PasswordHasher.verifyPassword(rawPassword, hash, salt)) {
                    return new AccountResult(Status.INVALID_CREDENTIALS, null, "Invalid login or password.");
                }

                long id = rs.getLong("id");
                long now = System.currentTimeMillis();
                try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE accounts SET last_name = COALESCE(?, last_name), updated_at = ? WHERE id = ?"
                )) {
                    upd.setString(1, playerName);
                    upd.setLong(2, now);
                    upd.setLong(3, id);
                    upd.executeUpdate();
                }

                AccountRecord account = new AccountRecord(
                    id,
                    rs.getString("uuid"),
                    rs.getString("login"),
                    playerName != null ? playerName : rs.getString("last_name"),
                    rs.getLong("created_at"),
                    now
                );
                return new AccountResult(Status.OK, account, "Logged in as '" + account.login + "'.");
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in authenticate", e);
            return new AccountResult(Status.DB_ERROR, null, "Database error during login.");
        }
    }

    public synchronized AccountResult changePassword(long accountId, String oldPassword, String newPassword) {
        ensureOpen();
        if (newPassword == null
            || newPassword.length() < MIN_PASSWORD_LENGTH
            || newPassword.length() > MAX_PASSWORD_LENGTH) {
            return new AccountResult(
                Status.INVALID_PASSWORD_LENGTH,
                null,
                "New password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters."
            );
        }

        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT login, password_hash, password_salt FROM accounts WHERE id = ?"
        )) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString("login") == null) {
                    return new AccountResult(Status.NOT_FOUND, null, "Only registered accounts have a password.");
                }
                if (!PasswordHasher.verifyPassword(oldPassword, rs.getString("password_hash"), rs.getString("password_salt"))) {
                    return new AccountResult(Status.INVALID_CREDENTIALS, null, "Current password is incorrect.");
                }
            }

            PasswordHasher.HashedPassword hp = PasswordHasher.hashPassword(newPassword);
            long now = System.currentTimeMillis();
            try (PreparedStatement upd = connection.prepareStatement(
                "UPDATE accounts SET password_hash = ?, password_salt = ?, updated_at = ? WHERE id = ?"
            )) {
                upd.setString(1, hp.hashBase64());
                upd.setString(2, hp.saltBase64());
                upd.setLong(3, now);
                upd.setLong(4, accountId);
                upd.executeUpdate();
            }
            return new AccountResult(Status.OK, getByIdInternal(accountId), "Password updated.");
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in changePassword", e);
            return new AccountResult(Status.DB_ERROR, null, "Database error updating password.");
        }
    }

    public synchronized AccountResult adminSetPassword(String rawLogin, String newPassword) {
        ensureOpen();
        if (newPassword == null
            || newPassword.length() < MIN_PASSWORD_LENGTH
            || newPassword.length() > MAX_PASSWORD_LENGTH) {
            return new AccountResult(
                Status.INVALID_PASSWORD_LENGTH,
                null,
                "Password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters."
            );
        }
        String loginLower = rawLogin == null ? "" : rawLogin.trim().toLowerCase(Locale.ROOT);
        try {
            AccountRecord acc = findByLoginOrUuid(loginLower);
            if (acc == null || !acc.isRegistered() || !acc.login.equalsIgnoreCase(loginLower)) {
                return new AccountResult(Status.NOT_FOUND, null, "Registered login '" + rawLogin + "' not found.");
            }
            PasswordHasher.HashedPassword hp = PasswordHasher.hashPassword(newPassword);
            long now = System.currentTimeMillis();
            try (PreparedStatement upd = connection.prepareStatement(
                "UPDATE accounts SET password_hash = ?, password_salt = ?, updated_at = ? WHERE id = ?"
            )) {
                upd.setString(1, hp.hashBase64());
                upd.setString(2, hp.saltBase64());
                upd.setLong(3, now);
                upd.setLong(4, acc.id);
                upd.executeUpdate();
            }
            return new AccountResult(Status.OK, getByIdInternal(acc.id), "Updated password for '" + acc.login + "'.");
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in adminSetPassword", e);
            return new AccountResult(Status.DB_ERROR, null, "Database error updating password.");
        }
    }

    public synchronized @Nullable AccountRecord findByLoginOrUuid(String loginOrUuid) {
        ensureOpen();
        if (loginOrUuid == null || loginOrUuid.isBlank()) return null;
        String trimmed = loginOrUuid.trim();
        try {
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, uuid, login, last_name, created_at, updated_at FROM accounts WHERE login_lower = ?"
            )) {
                ps.setString(1, trimmed.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapAccount(rs);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, uuid, login, last_name, created_at, updated_at FROM accounts WHERE uuid = ?"
            )) {
                ps.setString(1, trimmed);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapAccount(rs);
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, uuid, login, last_name, created_at, updated_at FROM accounts WHERE LOWER(last_name) = ? ORDER BY updated_at DESC LIMIT 1"
            )) {
                ps.setString(1, trimmed.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapAccount(rs);
                }
            }
            return null;
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in findByLoginOrUuid", e);
            return null;
        }
    }

    public synchronized @Nullable AccountRecord getById(long id) {
        ensureOpen();
        try {
            return getByIdInternal(id);
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getById", e);
            return null;
        }
    }

    // ------------------------------------------------------------------ per-faction unit persistence

    public synchronized @Nullable String getFactionUnitType(
        long accountId,
        String factionId,
        @Nullable String defaultUnitName
    ) {
        ensureOpen();
        String normalizedFaction = factionId.toLowerCase(Locale.ROOT);
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT unit_type FROM account_factions WHERE account_id = ? AND faction_id = ?"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("unit_type");
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getFactionUnitType", e);
        }
        return defaultUnitName;
    }

    public synchronized void setFactionUnitType(long accountId, String factionId, String unitTypeName) {
        ensureOpen();
        if (factionId == null || unitTypeName == null || unitTypeName.isEmpty()) return;
        String normalizedFaction = factionId.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO account_factions (account_id, faction_id, unit_type, extra_json, updated_at)
            VALUES (?, ?, ?, '{}', ?)
            ON CONFLICT(account_id, faction_id) DO UPDATE SET
                unit_type = excluded.unit_type,
                updated_at = excluded.updated_at
            """)) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.setString(3, unitTypeName);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in setFactionUnitType", e);
        }
    }

    public synchronized String getFactionExtraJson(long accountId, String factionId) {
        ensureOpen();
        String normalizedFaction = factionId.toLowerCase(Locale.ROOT);
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT extra_json FROM account_factions WHERE account_id = ? AND faction_id = ?"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString("extra_json");
                    if (val != null && !val.isEmpty()) return val;
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getFactionExtraJson", e);
        }
        return "{}";
    }

    public synchronized void setFactionExtraJson(long accountId, String factionId, String extraJson) {
        ensureOpen();
        String normalizedFaction = factionId.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO account_factions (account_id, faction_id, unit_type, extra_json, updated_at)
            VALUES (?, ?, NULL, ?, ?)
            ON CONFLICT(account_id, faction_id) DO UPDATE SET
                extra_json = excluded.extra_json,
                updated_at = excluded.updated_at
            """)) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.setString(3, extraJson == null ? "{}" : extraJson);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in setFactionExtraJson", e);
        }
    }

    public synchronized Seq<FactionProfileRecord> getFactionProfiles(long accountId) {
        ensureOpen();
        Seq<FactionProfileRecord> out = new Seq<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT account_id, faction_id, unit_type, extra_json, updated_at FROM account_factions WHERE account_id = ? ORDER BY faction_id"
        )) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new FactionProfileRecord(
                        rs.getLong("account_id"),
                        rs.getString("faction_id"),
                        rs.getString("unit_type"),
                        rs.getString("extra_json"),
                        rs.getLong("updated_at")
                    ));
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getFactionProfiles", e);
        }
        return out;
    }

    // ------------------------------------------------------------------ per-faction Tech Tree persistence

    public synchronized TechProgressData getFactionTechProgress(long accountId, String factionId) {
        ensureOpen();
        String normalizedFaction = normalizeFaction(factionId);
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT unlocked_json, paid_json, flags_json FROM account_tech_progress WHERE account_id = ? AND faction_id = ?"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    TechProgressData data = new TechProgressData(true);
                    parseStringArrayJson(rs.getString("unlocked_json"), data.unlocked);
                    parseStringArrayJson(rs.getString("flags_json"), data.flags);
                    parsePaidMapJson(rs.getString("paid_json"), data.paidItems);
                    return data;
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getFactionTechProgress", e);
        }
        return new TechProgressData(false);
    }

    public synchronized void saveFactionTechProgress(
        long accountId,
        String factionId,
        ObjectSet<String> unlocked,
        ObjectSet<String> flags,
        ObjectMap<String, ObjectIntMap<String>> paidItems
    ) {
        ensureOpen();
        String normalizedFaction = normalizeFaction(factionId);
        long now = System.currentTimeMillis();
        String unlockedJson = toStringArrayJson(unlocked);
        String flagsJson = toStringArrayJson(flags);
        String paidJson = toPaidMapJson(paidItems);

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO account_tech_progress (account_id, faction_id, unlocked_json, paid_json, flags_json, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(account_id, faction_id) DO UPDATE SET
                unlocked_json = excluded.unlocked_json,
                paid_json = excluded.paid_json,
                flags_json = excluded.flags_json,
                updated_at = excluded.updated_at
            """)) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.setString(3, unlockedJson);
            ps.setString(4, paidJson);
            ps.setString(5, flagsJson);
            ps.setLong(6, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in saveFactionTechProgress", e);
        }
    }

    public synchronized void resetFactionTechProgress(long accountId, String factionId) {
        ensureOpen();
        String normalizedFaction = normalizeFaction(factionId);
        try (PreparedStatement ps = connection.prepareStatement(
            "DELETE FROM account_tech_progress WHERE account_id = ? AND faction_id = ?"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in resetFactionTechProgress", e);
        }
    }

    public synchronized void resetAllTechProgress() {
        ensureOpen();
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM account_tech_progress");
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in resetAllTechProgress", e);
        }
    }

    // ------------------------------------------------------------------ per-faction orbital storage (Launch Pads)

    public synchronized ObjectIntMap<String> getOrbitalStorage(long accountId, String factionId) {
        ensureOpen();
        String normalizedFaction = normalizeFaction(factionId);
        ObjectIntMap<String> out = new ObjectIntMap<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT item_name, amount FROM account_orbital_storage WHERE account_id = ? AND faction_id = ? AND amount > 0"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("item_name");
                    int amt = rs.getInt("amount");
                    if (name != null && amt > 0) {
                        out.put(name, amt);
                    }
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getOrbitalStorage", e);
        }
        return out;
    }

    public synchronized int getOrbitalItemAmount(long accountId, String factionId, String itemName) {
        ensureOpen();
        if (itemName == null || itemName.isEmpty()) return 0;
        String normalizedFaction = normalizeFaction(factionId);
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT amount FROM account_orbital_storage WHERE account_id = ? AND faction_id = ? AND item_name = ?"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.setString(3, itemName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Math.max(0, rs.getInt("amount"));
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getOrbitalItemAmount", e);
        }
        return 0;
    }

    /**
     * Adds {@code delta} of {@code itemName} into persistent orbital storage, saturating at
     * {@link Integer#MAX_VALUE} ({@code 2,147,483,647}) without signed 32-bit overflow.
     *
     * @return the new stored amount.
     */
    public synchronized int addOrbitalItem(long accountId, String factionId, String itemName, int delta) {
        ensureOpen();
        if (itemName == null || itemName.isEmpty()) return 0;
        int current = getOrbitalItemAmount(accountId, factionId, itemName);
        if (delta == 0) return current;
        long sum = (long) current + (long) delta;
        int next = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, sum));
        setOrbitalItemAmount(accountId, factionId, itemName, next);
        return next;
    }

    public synchronized boolean takeOrbitalItem(long accountId, String factionId, String itemName, int amount) {
        ensureOpen();
        if (amount <= 0) return true;
        int current = getOrbitalItemAmount(accountId, factionId, itemName);
        if (current < amount) return false;
        setOrbitalItemAmount(accountId, factionId, itemName, current - amount);
        return true;
    }

    public synchronized void setOrbitalItemAmount(long accountId, String factionId, String itemName, int amount) {
        ensureOpen();
        if (itemName == null || itemName.isEmpty()) return;
        String normalizedFaction = normalizeFaction(factionId);
        int clamped = Math.max(0, amount);
        long now = System.currentTimeMillis();

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO account_orbital_storage (account_id, faction_id, item_name, amount, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(account_id, faction_id, item_name) DO UPDATE SET
                amount = excluded.amount,
                updated_at = excluded.updated_at
            """)) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.setString(3, itemName);
            ps.setInt(4, clamped);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in setOrbitalItemAmount", e);
        }
    }

    /** Fills every item in {@code itemNames} in persistent orbital storage up to {@link Integer#MAX_VALUE}. */
    public synchronized int fillOrbitalStorageToMax(long accountId, String factionId, Iterable<String> itemNames) {
        ensureOpen();
        if (itemNames == null) return 0;
        String normalizedFaction = normalizeFaction(factionId);
        long now = System.currentTimeMillis();
        int count = 0;

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO account_orbital_storage (account_id, faction_id, item_name, amount, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(account_id, faction_id, item_name) DO UPDATE SET
                amount = excluded.amount,
                updated_at = excluded.updated_at
            """)) {
            for (String itemName : itemNames) {
                if (itemName == null || itemName.isEmpty()) continue;
                ps.setLong(1, accountId);
                ps.setString(2, normalizedFaction);
                ps.setString(3, itemName);
                ps.setInt(4, Integer.MAX_VALUE);
                ps.setLong(5, now);
                ps.addBatch();
                count++;
            }
            ps.executeBatch();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in fillOrbitalStorageToMax", e);
        }
        return count;
    }

    // ------------------------------------------------------------------ per-faction equipped items & bag

    public synchronized EquipmentData getEquipmentData(long accountId, String factionId) {
        ensureOpen();
        String normalizedFaction = normalizeFaction(factionId);
        EquipmentData out = new EquipmentData();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT equipped_json, stash_json FROM account_equipped_items WHERE account_id = ? AND faction_id = ?"
        )) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    parseStringSeqJson(rs.getString("equipped_json"), out.equipped);
                    parseIntMapJson(rs.getString("stash_json"), out.stash);
                }
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in getEquipmentData", e);
        }
        return out;
    }

    public synchronized void saveEquipmentData(long accountId, String factionId, EquipmentData data) {
        ensureOpen();
        if (data == null) return;
        String normalizedFaction = normalizeFaction(factionId);
        long now = System.currentTimeMillis();
        String eqJson = toStringSeqJson(data.equipped);
        String stashJson = toIntMapJson(data.stash);

        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO account_equipped_items (account_id, faction_id, equipped_json, stash_json, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(account_id, faction_id) DO UPDATE SET
                equipped_json = excluded.equipped_json,
                stash_json = excluded.stash_json,
                updated_at = excluded.updated_at
            """)) {
            ps.setLong(1, accountId);
            ps.setString(2, normalizedFaction);
            ps.setString(3, eqJson);
            ps.setString(4, stashJson);
            ps.setLong(5, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.err("[CoolPlugin] SQLite error in saveEquipmentData", e);
        }
    }

    public synchronized Stats getStats() {
        ensureOpen();
        int total = queryCount("SELECT COUNT(*) FROM accounts");
        int reg = queryCount("SELECT COUNT(*) FROM accounts WHERE login IS NOT NULL");
        int factions = queryCount("SELECT COUNT(*) FROM account_factions");
        int techRows = queryCount("SELECT COUNT(*) FROM account_tech_progress");
        return new Stats(total, reg, total - reg, factions, techRows);
    }

    private static String normalizeFaction(@Nullable String factionId) {
        if (factionId == null || factionId.isBlank()) return "sharded";
        return factionId.trim().toLowerCase(Locale.ROOT);
    }

    private static void parseStringArrayJson(@Nullable String json, ObjectSet<String> target) {
        if (json == null || json.isBlank()) return;
        try {
            JsonValue arr = new JsonReader().parse(json);
            if (arr == null) return;
            for (JsonValue v = arr.child; v != null; v = v.next) {
                String s = v.asString();
                if (s != null && !s.isEmpty()) target.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    private static void parseStringSeqJson(@Nullable String json, Seq<String> target) {
        if (json == null || json.isBlank()) return;
        try {
            JsonValue arr = new JsonReader().parse(json);
            if (arr == null) return;
            for (JsonValue v = arr.child; v != null; v = v.next) {
                String s = v.asString();
                if (s != null && !s.isEmpty()) target.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    private static void parseIntMapJson(@Nullable String json, ObjectIntMap<String> target) {
        if (json == null || json.isBlank()) return;
        try {
            JsonValue root = new JsonReader().parse(json);
            if (root == null) return;
            for (JsonValue e = root.child; e != null; e = e.next) {
                int amt = e.asInt();
                if (e.name != null && amt > 0) {
                    target.put(e.name, amt);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String toStringSeqJson(Seq<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (int i = 0; i < list.size; i++) {
            String s = list.get(i);
            if (s == null || s.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(s)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String toIntMapJson(ObjectIntMap<String> map) {
        if (map == null || map.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (var e : map.entries()) {
            if (e.key == null || e.value <= 0) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(e.key)).append("\":").append(e.value);
        }
        sb.append('}');
        return sb.toString();
    }

    private static void parsePaidMapJson(@Nullable String json, ObjectMap<String, ObjectIntMap<String>> target) {
        if (json == null || json.isBlank()) return;
        try {
            JsonValue root = new JsonReader().parse(json);
            if (root == null) return;
            for (JsonValue nodeEntry = root.child; nodeEntry != null; nodeEntry = nodeEntry.next) {
                if (nodeEntry.name == null) continue;
                ObjectIntMap<String> items = new ObjectIntMap<>();
                for (JsonValue itemEntry = nodeEntry.child; itemEntry != null; itemEntry = itemEntry.next) {
                    int amt = itemEntry.asInt();
                    if (itemEntry.name != null && amt > 0) {
                        items.put(itemEntry.name, amt);
                    }
                }
                if (!items.isEmpty()) {
                    target.put(nodeEntry.name, items);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String toStringArrayJson(ObjectSet<String> set) {
        if (set == null || set.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        boolean first = true;
        for (String s : set) {
            if (s == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escapeJson(s)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String toPaidMapJson(ObjectMap<String, ObjectIntMap<String>> paid) {
        if (paid == null || paid.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean firstNode = true;
        for (var nodeEntry : paid.entries()) {
            if (nodeEntry.value == null || nodeEntry.value.isEmpty()) continue;
            if (!firstNode) sb.append(',');
            firstNode = false;
            sb.append('"').append(escapeJson(nodeEntry.key)).append("\":{");
            boolean firstItem = true;
            for (var itemEntry : nodeEntry.value.entries()) {
                if (itemEntry.value <= 0) continue;
                if (!firstItem) sb.append(',');
                firstItem = false;
                sb.append('"').append(escapeJson(itemEntry.key)).append("\":").append(itemEntry.value);
            }
            sb.append('}');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private int queryCount(String sql) {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return 0;
        }
    }

    private @Nullable AccountRecord getByIdInternal(long id) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT id, uuid, login, last_name, created_at, updated_at FROM accounts WHERE id = ?"
        )) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapAccount(rs) : null;
            }
        }
    }

    /**
     * Wipes all gameplay data for {@code accountId} across every faction:
     * saved faction units, Tech Tree unlocks/payments/flags, persistent Launch Pad orbital storage,
     * and equipped/stashed shop items.
     */
    public synchronized boolean wipeAccountData(long accountId) {
        ensureOpen();
        try {
            boolean origAuto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (String table : new String[]{
                    "account_factions",
                    "account_tech_progress",
                    "account_orbital_storage",
                    "account_equipped_items"
                }) {
                    try (PreparedStatement del = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE account_id = ?"
                    )) {
                        del.setLong(1, accountId);
                        del.executeUpdate();
                    }
                }
                try (PreparedStatement upd = connection.prepareStatement(
                    "UPDATE accounts SET updated_at = ? WHERE id = ?"
                )) {
                    upd.setLong(1, System.currentTimeMillis());
                    upd.setLong(2, accountId);
                    upd.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(origAuto);
            }
        } catch (SQLException e) {
            Log.err("[CoolPlugin] Failed wiping data for account #" + accountId, e);
            return false;
        }
    }

    private static AccountRecord mapAccount(ResultSet rs) throws SQLException {
        return new AccountRecord(
            rs.getLong("id"),
            rs.getString("uuid"),
            rs.getString("login"),
            rs.getString("last_name"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }

    private void ensureOpen() {
        if (connection == null) {
            init();
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            } finally {
                connection = null;
            }
        }
    }
}
