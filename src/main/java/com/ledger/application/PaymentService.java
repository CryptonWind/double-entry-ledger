package com.ledger.application;

import com.ledger.domain.service.MoneyRules;
import com.ledger.application.port.out.UnitOfWork;
import com.ledger.application.port.out.JsonCodec;
import com.ledger.domain.exception.LedgerException;
import com.ledger.application.model.LedgerModels.EntryInput;
import com.ledger.application.model.LedgerModels.RecordTransaction;
import com.ledger.application.port.in.TransactionUseCases;
import com.ledger.domain.model.Direction;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import com.ledger.application.port.out.AccountStore;
import com.ledger.application.port.out.PaymentStore;
import lombok.extern.log4j.Log4j2;

@RequiredArgsConstructor
@Log4j2
public class PaymentService implements com.ledger.application.port.in.SettlePayment {
    private final UnitOfWork transactions;
    private final PaymentStore store;
    private final AccountStore accounts;
    private final JsonCodec json;
    private final TransactionUseCases ledger;

    public record PaymentSettled(String eventId, String paymentId, String merchantAccountId,
                                 BigDecimal amount, String currency) { }
    private record PaymentIdentity(String merchantAccountId, BigDecimal amount, String currency) { }

    public String settle(PaymentSettled payment) {
        if (payment.eventId() == null || payment.eventId().isBlank() || payment.eventId().length() > 128
                || payment.paymentId() == null || !payment.paymentId().matches("[A-Za-z0-9._:-]{1,128}")
                || payment.merchantAccountId() == null || payment.merchantAccountId().isBlank()
                || payment.merchantAccountId().length() > 64) {
            throw LedgerException.invalid("INVALID_PAYMENT", "payment", "Event ID, payment ID and merchant account are required");
        }
        BigDecimal amount = MoneyRules.amount(payment.amount(), payment.currency(), "amount", false);
        String fingerprint = json.fingerprint(new PaymentIdentity(payment.merchantAccountId(), amount, payment.currency()));
        return transactions.execute(() -> {
            var stored = store.reserve(payment.paymentId(), fingerprint);
            if (!fingerprint.equals(stored.fingerprint())) {
                throw new LedgerException(409, "PAYMENT_CONFLICT", "paymentId", "Payment ID was already recorded with different details");
            }
            if (stored.transactionId() != null) {
                log.info("Replaying settled payment paymentId={} transactionId={}", payment.paymentId(), stored.transactionId());
                return stored.transactionId();
            }
            // Scalar query avoids caching a merchant entity before the ordered account locks.
            var currencies = accounts.currency(payment.merchantAccountId()).stream().toList();
            if (currencies.isEmpty()) {
                throw LedgerException.invalid("UNKNOWN_ACCOUNT", "merchantAccountId", "Unknown merchant account");
            }
            if (!currencies.get(0).equals(payment.currency())) {
                throw LedgerException.invalid("WRONG_CURRENCY", "currency", "Payment currency differs from merchant account currency");
            }
            if (payment.merchantAccountId().startsWith("settlement-")) {
                throw LedgerException.invalid("INVALID_MERCHANT", "merchantAccountId", "Settlement accounts cannot be merchant accounts");
            }
            var result = ledger.record(new RecordTransaction("Settled payment " + payment.paymentId(), List.of(
                    new EntryInput("settlement-" + payment.currency(), Direction.DEBIT, amount),
                    new EntryInput(payment.merchantAccountId(), Direction.CREDIT, amount))));
            store.complete(payment.paymentId(), result.id());
            log.info("Settled payment paymentId={} eventId={} transactionId={}", payment.paymentId(), payment.eventId(), result.id());
            return result.id();
        });
    }
}
