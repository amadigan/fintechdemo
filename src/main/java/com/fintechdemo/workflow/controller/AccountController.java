package com.fintechdemo.workflow.controller;

import com.fintechdemo.workflow.model.Account;
import com.fintechdemo.workflow.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/api/accounts")
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest request) {
        log.info("Creating new account: {}", request);
        Account createdAccount = accountService.createAccount(
            request.getCustomerId(), 
            request.getName(), 
            request.getCurrency()
        );
        return ResponseEntity.status(201).body(createdAccount);
    }

    @GetMapping("/api/accounts/{id}")
    public ResponseEntity<Account> getAccount(@PathVariable UUID id) {
        log.info("Getting account with id: {}", id);
        Account account = accountService.getAccount(id.toString());
        return ResponseEntity.ok(account);
    }

    @GetMapping("/api/customers/{customerId}/accounts")
    public ResponseEntity<List<Account>> getCustomerAccounts(@PathVariable UUID customerId) {
        log.info("Getting accounts for customer: {}", customerId);
        List<Account> accounts = accountService.getCustomerAccounts(customerId.toString());
        return ResponseEntity.ok(accounts);
    }
} 
