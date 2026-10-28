package com.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        var application = new SpringApplication(LedgerApplication.class);
        var context = application.run(args);
        if (context.getEnvironment().getProperty("ledger.payments.replay", Boolean.class, false)) {
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }
    }
}
