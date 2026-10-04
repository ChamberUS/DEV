package panel.adapter;

import panel.model.*;

public interface ByxPaymentVerifier {
    PaymentReceipt verify(ByxConfig config, PaymentIntent intent, String txHash, long passSeconds) throws Exception;
}
