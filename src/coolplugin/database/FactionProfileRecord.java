package coolplugin.database;

/**
 * Per-faction persistent data for an account (`account_factions` table).
 * Currently persists {@code unitTypeName} separately for each faction, plus an
 * extensible {@code extraJson} payload column ready for future faction progression/state.
 */
public final class FactionProfileRecord {

    public final long accountId;
    public final String factionId;
    public final String unitTypeName;
    public final String extraJson;
    public final long updatedAt;

    public FactionProfileRecord(
        long accountId,
        String factionId,
        String unitTypeName,
        String extraJson,
        long updatedAt
    ) {
        this.accountId = accountId;
        this.factionId = factionId;
        this.unitTypeName = unitTypeName;
        this.extraJson = extraJson == null ? "{}" : extraJson;
        this.updatedAt = updatedAt;
    }
}
