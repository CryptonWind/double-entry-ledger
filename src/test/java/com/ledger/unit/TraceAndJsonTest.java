package com.ledger.unit;

import com.ledger.infrastructure.tracing.TraceContext;
import com.ledger.support.UnitTestBase;
import com.ledger.application.model.LedgerModels.OpenAccount;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;

class TraceAndJsonTest extends UnitTestBase {
    @Test
    void nestedTraceScopesRestoreOuterContextAndKeepTraceIdentity() {
        String parent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01";
        try (var outer = TraceContext.open("outer", parent)) {
            String child = TraceContext.child(parent);
            assertThat(child).startsWith("00-1234567890abcdef1234567890abcdef-").isNotEqualTo(parent);
            try (var inner = TraceContext.open("inner", child)) {
                assertThat(TraceContext.correlationId()).isEqualTo("inner");
            }
            assertThat(TraceContext.correlationId()).isEqualTo("outer");
            assertThat(TraceContext.traceparent()).isEqualTo(parent);
        }
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void invalidTraceAndCorrelationAreReplacedWithSafeGeneratedValues() {
        try (var ignored = TraceContext.open("invalid\nheader", "invalid")) {
            assertThat(TraceContext.correlationId()).matches("[a-f0-9-]{36}");
            assertThat(TraceContext.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        }
    }

    @Test
    void dtoFingerprintsIgnoreJsonFormattingButDetectDifferentRequests() {
        var first = json.read("{\"currency\":\"EUR\",\"overdraftLimit\":0}", OpenAccount.class);
        var retry = json.read("{ \"overdraftLimit\": 0, \"currency\": \"EUR\" }", OpenAccount.class);
        assertThat(json.fingerprint(first)).isEqualTo(json.fingerprint(retry));
        assertThat(json.fingerprint(first)).isNotEqualTo(json.fingerprint(new OpenAccount("USD", BigDecimal.ZERO)));
        assertThat(json.read(json.write(first), OpenAccount.class)).isEqualTo(first);
    }
}
