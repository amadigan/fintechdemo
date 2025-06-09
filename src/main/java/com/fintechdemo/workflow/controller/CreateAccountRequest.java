package com.fintechdemo.workflow.controller;

import lombok.Data;

@Data
public class CreateAccountRequest {
    private String customerId;
    private String name; // Account name like "checking", "savings", etc.
    private String currency; // ISO currency code like "USD", "EUR"
} 
