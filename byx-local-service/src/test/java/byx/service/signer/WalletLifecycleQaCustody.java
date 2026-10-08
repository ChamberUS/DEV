package byx.service.signer;

import byx.service.tx.TxPorts.TxSignRequest;

final class WalletLifecycleQaCustody implements WalletLifecycle.Custody {
    private final CustodyClient client;
    private final String point;
    WalletLifecycleQaCustody(CustodyClient client,String point) { this.client=client;this.point=point; }
    public void acquire() throws WalletLifecycle.CustodyFailure {
        try { client.authority(); }
        catch(CustodyClient.CustodyException e) { throw new WalletLifecycle.CustodyFailure(e.code()); }
    }
    public WalletLifecycle.Reply call(String op,WalletLifecycle.Request request,TxSignRequest sign) throws WalletLifecycle.CustodyFailure {
        String probe=java.util.Set.of("provisionBound","revokeDeleteBound","signBound").contains(op)?point:"";
        var wire=new CustodyClient.LifecycleRequest(request.binding(),request.operationId(),request.requestDigest(),request.expectedVersion(),request.publicKey(),request.address(),request.offset(),request.snapshotDigest(),probe);
        try {
            var reply=client.lifecycle(op,wire,sign);
            return new WalletLifecycle.Reply(reply.status(),reply.keychainCalls(),reply.publicKey(),reply.address(),reply.count(),reply.response(),reply.lifecycle());
        } catch(CustodyClient.CustodyException e) { throw new WalletLifecycle.CustodyFailure(e.code()); }
    }
}
