package panel.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import panel.security.Database;

/** Public audit journal: a lifetime V1 quota per user AND wallet on this chain. */
public final class GasGrantRepository {
    public record Entry(long userId, String address, String chain, String genesis, String granter, BigInteger limit, Instant expiration, String state, String txHash, BigInteger remaining) { }
    private final Database db;
    public GasGrantRepository(Database db) {
        this.db = db;
        db.with(c -> { try (var s = c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS byx_gas_grants(user_id INTEGER NOT NULL,address TEXT NOT NULL,chain TEXT NOT NULL,genesis TEXT NOT NULL,granter TEXT NOT NULL,spend_limit TEXT NOT NULL,expiration TEXT NOT NULL,state TEXT NOT NULL,tx_hash TEXT NOT NULL,remaining TEXT NOT NULL,PRIMARY KEY(user_id,chain,genesis),UNIQUE(address,chain,genesis))"); } return null; });
    }
    public List<Entry> all() {
        return db.with(c -> { var out = new ArrayList<Entry>(); try (var q = c.createStatement(); var r = q.executeQuery("SELECT * FROM byx_gas_grants")) {
            while (r.next()) out.add(new Entry(r.getLong("user_id"), r.getString("address"), r.getString("chain"), r.getString("genesis"), r.getString("granter"), new BigInteger(r.getString("spend_limit")), Instant.parse(r.getString("expiration")), r.getString("state"), r.getString("tx_hash"), new BigInteger(r.getString("remaining"))));
        } return List.copyOf(out); });
    }
    public Optional<Entry> own(long user, String address, String chain, String genesis) {
        return all().stream().filter(e -> e.userId() == user && e.address().equals(address) && e.chain().equals(chain) && e.genesis().equals(genesis)).findFirst();
    }
    public void claim(Entry e) {
        db.with(c -> { try (var q = c.prepareStatement("INSERT INTO byx_gas_grants VALUES(?,?,?,?,?,?,?,'PENDING','',?)")) {
            q.setLong(1,e.userId()); q.setString(2,e.address()); q.setString(3,e.chain()); q.setString(4,e.genesis()); q.setString(5,e.granter()); q.setString(6,e.limit().toString()); q.setString(7,e.expiration().toString()); q.setString(8,e.limit().toString()); q.executeUpdate();
        } return null; });
    }
    public void observed(Entry e, BigInteger remaining) {
        db.with(c -> { try (var q=c.prepareStatement("UPDATE byx_gas_grants SET remaining=? WHERE user_id=? AND chain=? AND genesis=?")) {
            q.setString(1,remaining.toString()); q.setLong(2,e.userId()); q.setString(3,e.chain()); q.setString(4,e.genesis()); q.executeUpdate();
        } return null; });
    }
    public void update(Entry e, String state, String tx) {
        db.with(c -> { try (var q = c.prepareStatement("UPDATE byx_gas_grants SET state=?,tx_hash=? WHERE user_id=? AND address=? AND chain=? AND genesis=?")) {
            q.setString(1,state); q.setString(2,tx); q.setLong(3,e.userId()); q.setString(4,e.address()); q.setString(5,e.chain()); q.setString(6,e.genesis()); q.executeUpdate();
        } return null; });
    }
}
