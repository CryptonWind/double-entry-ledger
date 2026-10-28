package com.ledger.application.port.in;

import com.ledger.application.PaymentService.PaymentSettled;

public interface SettlePayment {
    String settle(PaymentSettled payment);
}
