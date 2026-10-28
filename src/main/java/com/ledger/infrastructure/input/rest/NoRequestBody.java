package com.ledger.infrastructure.input.rest;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NoRequestBody { }
