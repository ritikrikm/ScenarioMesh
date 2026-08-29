package example;

import org.junit.jupiter.api.Test;

class CheckoutIT {
    @Test
    void cardPayment() {
        IntegrationRecorder.record("CheckoutIT#cardPayment");
    }

    @Test
    void bankTransfer() {
        IntegrationRecorder.record("CheckoutIT#bankTransfer");
    }
}
