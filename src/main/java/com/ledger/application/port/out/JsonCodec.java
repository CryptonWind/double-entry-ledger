package com.ledger.application.port.out;

public interface JsonCodec {
    String write(Object value);
    <T> T read(String value, Class<T> type);
    String fingerprint(Object value);
}
