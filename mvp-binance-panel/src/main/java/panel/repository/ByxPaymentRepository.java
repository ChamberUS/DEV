package panel.repository;

import panel.security.Database;
import panel.model.*;
import java.time.Instant;
import java.math.BigInteger;
import java.util.*;
import java.sql.*;

public final class ByxPaymentRepository {
    private final Database db;
    public ByxPaymentRepository(Database db){this.db=db;db.with(c->{try(var s=c.createStatement()){
        s.execute("CREATE TABLE IF NOT EXISTS byx_payment_intents(id TEXT PRIMARY KEY,user_id INTEGER NOT NULL,wallet TEXT NOT NULL,chain_id TEXT NOT NULL,genesis TEXT NOT NULL,recipient TEXT NOT NULL,amount TEXT NOT NULL,purpose TEXT NOT NULL,created_at TEXT NOT NULL,expires_at TEXT NOT NULL,status TEXT NOT NULL,FOREIGN KEY(user_id) REFERENCES users(id))");
        s.execute("CREATE TABLE IF NOT EXISTS byx_payment_receipts(intent_id TEXT PRIMARY KEY,user_id INTEGER NOT NULL,wallet TEXT NOT NULL,chain_id TEXT NOT NULL,genesis TEXT NOT NULL,tx_hash TEXT NOT NULL,height INTEGER NOT NULL,amount TEXT NOT NULL,confirmed_at TEXT NOT NULL,starts_at TEXT NOT NULL,expires_at TEXT NOT NULL,UNIQUE(chain_id,genesis,tx_hash),FOREIGN KEY(intent_id) REFERENCES byx_payment_intents(id))");
    }return null;});}
    public void create(PaymentIntent i){db.with(c->{try(var q=c.prepareStatement("INSERT INTO byx_payment_intents VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
        q.setString(1,i.id());q.setLong(2,i.userId());q.setString(3,i.verifiedWallet());q.setString(4,i.chainId());q.setString(5,i.genesisFingerprint());q.setString(6,i.recipient());q.setString(7,i.amountUbyx().toString());q.setString(8,i.purpose());q.setString(9,i.createdAt().toString());q.setString(10,i.expiresAt().toString());q.setString(11,i.status().name());q.executeUpdate();
    }return null;});}
    public PaymentIntent get(long user,String id){return db.with(c->{try(var q=c.prepareStatement("SELECT * FROM byx_payment_intents WHERE id=? AND user_id=?")){
        q.setString(1,id);q.setLong(2,user);try(var r=q.executeQuery()){if(!r.next())throw new panel.security.AccessDeniedException("Unknown intent");return new PaymentIntent(r.getString("id"),r.getLong("user_id"),r.getString("wallet"),r.getString("chain_id"),r.getString("genesis"),r.getString("recipient"),new BigInteger(r.getString("amount")),r.getString("purpose"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("expires_at")),PaymentIntent.Status.valueOf(r.getString("status")));}
    }});}
    public void status(PaymentIntent i,PaymentIntent.Status status){db.with(c->{try(var q=c.prepareStatement("UPDATE byx_payment_intents SET status=? WHERE id=? AND user_id=?")){q.setString(1,status.name());q.setString(2,i.id());q.setLong(3,i.userId());q.executeUpdate();}return null;});}
    public void claim(PaymentIntent i){db.with(c->{try(var q=c.prepareStatement("UPDATE byx_payment_intents SET status='CONFIRMING' WHERE id=? AND user_id=? AND status IN ('CREATED','AWAITING_PAYMENT')")){
        q.setString(1,i.id());q.setLong(2,i.userId());if(q.executeUpdate()!=1)throw new panel.security.AccessDeniedException("Intent confirming or consumed");
    }return null;});}
    public void consume(PaymentIntent i,PaymentReceipt r){db.with(c->{c.setAutoCommit(false);try{
        try(var paid=c.prepareStatement("UPDATE byx_payment_intents SET status='PAID' WHERE id=? AND status='CONFIRMING'")){
            paid.setString(1,i.id());if(paid.executeUpdate()!=1)throw new SQLException("Intent unavailable");
        }
        try(var q=c.prepareStatement("INSERT INTO byx_payment_receipts VALUES(?,?,?,?,?,?,?,?,?,?,?)")){
            q.setString(1,r.paymentIntentId());q.setLong(2,r.userId());q.setString(3,r.wallet());q.setString(4,r.chainId());q.setString(5,r.genesisFingerprint());q.setString(6,r.txHash());q.setLong(7,r.height());q.setString(8,r.amountUbyx().toString());q.setString(9,r.confirmedAt().toString());q.setString(10,r.startsAt().toString());q.setString(11,r.expiresAt().toString());q.executeUpdate();
        }
        try(var q=c.prepareStatement("UPDATE byx_payment_intents SET status='CONSUMED' WHERE id=? AND user_id=? AND status='PAID'")){
            q.setString(1,i.id());q.setLong(2,i.userId());if(q.executeUpdate()!=1)throw new SQLException("Intent already consumed");
        }c.commit();
    }catch(SQLException e){c.rollback();throw e;}finally{c.setAutoCommit(true);}return null;});}
    public List<PaymentReceipt> receipts(long user){return db.with(c->{List<PaymentReceipt> result=new ArrayList<>();try(var q=c.prepareStatement("SELECT * FROM byx_payment_receipts WHERE user_id=? ORDER BY starts_at DESC")){
        q.setLong(1,user);try(var r=q.executeQuery()){while(r.next())result.add(new PaymentReceipt(r.getString("intent_id"),user,r.getString("wallet"),r.getString("chain_id"),r.getString("genesis"),r.getString("tx_hash"),r.getLong("height"),new BigInteger(r.getString("amount")),Instant.parse(r.getString("confirmed_at")),Instant.parse(r.getString("starts_at")),Instant.parse(r.getString("expires_at"))));}
    }return List.copyOf(result);});}
}
