package com.fintechdemo.workflow.controller;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class CreateDepositRequest {
    private String userId;
    private String currency;
    private BigDecimal amount;
    private Instant transactedAt;
    private String payorIBAN;        // IBAN of the sender
    private String originatingCountry;
    private String paymentRef;
    private String purposeRef;
} 
