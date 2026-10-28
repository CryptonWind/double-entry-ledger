package com.ledger.unit;

import com.ledger.infrastructure.input.rest.generated.api.AccountsApi;
import com.ledger.infrastructure.input.rest.generated.api.TransactionsApi;
import com.ledger.infrastructure.input.rest.generated.model.*;
import com.ledger.infrastructure.input.rest.AccountController;
import com.ledger.infrastructure.input.rest.TransactionController;
import com.ledger.infrastructure.output.serialization.JsonSupport;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class GeneratedContractTest {
    @Test void controllersImplementGeneratedContractsAndMoneyRemainsExact() {
        assertThat(AccountsApi.class.isAssignableFrom(AccountController.class)).isTrue();
        assertThat(TransactionsApi.class.isAssignableFrom(TransactionController.class)).isTrue();
        var json = new JsonSupport(tools.jackson.databind.json.JsonMapper.builder().build());
        var request = json.read("{\"description\":\"Settlement\",\"entries\":[{\"accountId\":\"merchant\",\"direction\":\"CREDIT\",\"amount\":\"125.50\"}]}", RecordTransactionDto.class);
        assertThat(request.getEntries().get(0).getAmount()).isEqualTo(new BigDecimal("125.50"));
        assertThat(json.read("{\"currency\":\"GBP\"}", OpenAccountDto.class).getCurrency()).isEqualTo("GBP");
    }
}
