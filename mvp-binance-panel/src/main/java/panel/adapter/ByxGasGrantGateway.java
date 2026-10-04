package panel.adapter;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import panel.model.ByxConfig;
import panel.model.GasGrantSnapshot;
import panel.service.GasSponsorshipPolicy;

public interface ByxGasGrantGateway {
    Optional<GasGrantSnapshot> read(ByxConfig config, GasSponsorshipPolicy policy, String grantee) throws Exception;
    String grant(ByxConfig config, GasSponsorshipPolicy policy, String grantee, BigInteger limit, Instant expiration) throws Exception;
    String revoke(ByxConfig config, GasSponsorshipPolicy policy, String grantee) throws Exception;
}
