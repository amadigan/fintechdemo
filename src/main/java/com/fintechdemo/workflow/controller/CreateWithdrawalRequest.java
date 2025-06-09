package com.fintechdemo.workflow.controller;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class CreateWithdrawalRequest {
    private String userId;
    private String currency;
    private BigDecimal amount;
    private Instant transactedAt;
    private String beneficiaryIBAN;  // IBAN of the recipient (required for withdrawals)
    private String originatingCountry;
    private String paymentRef;
    private String purposeRef;
} 
