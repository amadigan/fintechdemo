package com.fintechdemo.workflow.controller;

import com.fintechdemo.workflow.model.Transaction;
import lombok.Data;

import java.util.List;

@Data
public class TransactionListResponse {
    private List<Transaction> transactions;
    private String nextToken;  // For pagination through non-pending transactions
    
    public TransactionListResponse(List<Transaction> transactions, String nextToken) {
        this.transactions = transactions;
        this.nextToken = nextToken;
    }
} 
