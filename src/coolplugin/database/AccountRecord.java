package coolplugin.database;

import arc.util.Nullable;

/**
 * Immutable snapshot of an account row in {@code players.db}.
 * <ul>
 *   <li><b>Guest account:</b> {@code uuid != null && login == null} (auto-loaded by UUID).</li>
 *   <li><b>Secured account:</b> {@code uuid == null && login != null} (accessible ONLY via {@code /login <login> <password>}).</li>
 * </ul>
 */
public final class AccountRecord {

    public final long id;
    public final @Nullable String uuid;
    public final @Nullable String login;
    public final @Nullable String lastName;
    public final long createdAt;
    public final long updatedAt;

    public AccountRecord(
        long id,
        @Nullable String uuid,
        @Nullable String login,
        @Nullable String lastName,
        long createdAt,
        long updatedAt
    ) {
        this.id = id;
        this.uuid = uuid;
        this.login = login;
        this.lastName = lastName;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public boolean isRegistered() {
        return login != null && !login.isEmpty();
    }

    public boolean isGuest() {
        return !isRegistered();
    }
}
