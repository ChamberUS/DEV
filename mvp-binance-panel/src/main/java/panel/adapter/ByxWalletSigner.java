package panel.adapter;

import java.util.concurrent.CompletionStage;
import panel.model.WalletChallenge;
import panel.model.WalletProof;

/** External signers return public proofs only; no seed/key API. */
public interface ByxWalletSigner {
    CompletionStage<WalletProof> sign(WalletChallenge challenge);
}
