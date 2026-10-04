package panel.repository;

import java.time.Instant;
import java.util.*;
import panel.model.VerifiedWallet;
import panel.security.Database;

public final class ByxWalletRepository {
    private final Database db;
    public ByxWalletRepository(Database db) {
        this.db = db;
        db.with(c -> { try (var s = c.createStatement()) { s.execute("""
            CREATE TABLE IF NOT EXISTS verified_wallets (
              user_id INTEGER NOT NULL, address TEXT NOT NULL, public_key TEXT NOT NULL,
              chain_id TEXT NOT NULL, genesis_fingerprint TEXT NOT NULL,
              verified_at TEXT NOT NULL, last_verified_at TEXT NOT NULL, revoked_at TEXT,
              PRIMARY KEY(user_id,address,chain_id,genesis_fingerprint),
              FOREIGN KEY(user_id) REFERENCES users(id))
            """); } return null; });
    }
    public List<VerifiedWallet> list(long userId) {
        return db.with(c -> { var result = new ArrayList<VerifiedWallet>();
            try (var q = c.prepareStatement("SELECT * FROM verified_wallets WHERE user_id=? ORDER BY last_verified_at DESC")) {
                q.setLong(1, userId); try (var r = q.executeQuery()) { while (r.next()) result.add(new VerifiedWallet(userId,
                    r.getString("address"), r.getString("public_key"), r.getString("chain_id"), r.getString("genesis_fingerprint"),
                    Instant.parse(r.getString("verified_at")), Instant.parse(r.getString("last_verified_at")),
                    r.getString("revoked_at") == null ? null : Instant.parse(r.getString("revoked_at")))); }
            } return List.copyOf(result); });
    }
    public void save(VerifiedWallet w) {
        db.with(c -> { try (var q = c.prepareStatement("""
            INSERT INTO verified_wallets VALUES(?,?,?,?,?,?,?,NULL)
            ON CONFLICT(user_id,address,chain_id,genesis_fingerprint) DO UPDATE SET
            public_key=excluded.public_key,last_verified_at=excluded.last_verified_at,revoked_at=NULL
            """)) {
            q.setLong(1,w.userId()); q.setString(2,w.address()); q.setString(3,w.publicKey()); q.setString(4,w.chainId());
            q.setString(5,w.genesisFingerprint()); q.setString(6,w.verifiedAt().toString()); q.setString(7,w.lastVerifiedAt().toString()); q.executeUpdate();
        } return null; });
    }
    public void revoke(long user, String address, String chain, String genesis, Instant now) {
        db.with(c -> { try (var q = c.prepareStatement("UPDATE verified_wallets SET revoked_at=? WHERE user_id=? AND address=? AND chain_id=? AND genesis_fingerprint=?")) {
            q.setString(1,now.toString()); q.setLong(2,user); q.setString(3,address); q.setString(4,chain); q.setString(5,genesis); q.executeUpdate();
        } return null; });
    }
}
