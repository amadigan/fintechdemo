package com.fintechdemo.workflow.controller;

import com.fintechdemo.workflow.model.Transaction;
import com.fintechdemo.workflow.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.io.StringWriter;
import java.io.IOException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

@Slf4j
@RestController
@RequiredArgsConstructor
@Lazy  // Lazy initialization to prevent issues with SnapStart
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/api/accounts/{accountId}/deposit")
    public ResponseEntity<Transaction> createDeposit(
            @PathVariable String accountId,
            @RequestBody CreateDepositRequest request) {
        log.info("Creating deposit for account: {}", accountId);
        
        try {
            Transaction transaction = transactionService.createDeposit(
                accountId,
                request.getUserId(),
                request.getCurrency(),
                request.getAmount(),
                request.getTransactedAt(),
                request.getPayorIBAN(),
                request.getOriginatingCountry(),
                request.getPaymentRef(),
                request.getPurposeRef()
            );
            
            return ResponseEntity.status(201).body(transaction);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid deposit request for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to create deposit for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping("/api/accounts/{accountId}/transaction")
    public ResponseEntity<Transaction> createWithdrawal(
            @PathVariable String accountId,
            @RequestBody CreateWithdrawalRequest request) {
        log.info("Creating withdrawal for account: {}", accountId);
        
        try {
            Transaction transaction = transactionService.createWithdrawal(
                accountId,
                request.getUserId(),
                request.getCurrency(),
                request.getAmount(),
                request.getTransactedAt(),
                request.getBeneficiaryIBAN(),
                request.getOriginatingCountry(),
                request.getPaymentRef(),
                request.getPurposeRef()
            );
            
            return ResponseEntity.status(201).body(transaction);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid withdrawal request for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to create withdrawal for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/api/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionListResponse> getAccountTransactions(
            @PathVariable String accountId,
            @RequestParam(required = false) String nextToken,
            @RequestParam(required = false, defaultValue = "20") Integer limit) {
        log.info("Getting transactions for account: {}", accountId);
        
        try {
            TransactionListResponse response = transactionService.getAccountTransactions(accountId, nextToken, limit);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to get transactions for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/api/accounts/{accountId}/transactions.csv")
    public ResponseEntity<String> getTransactionsCsv(@PathVariable String accountId) {
        log.info("Getting CSV export of stamped transactions for current year for account: {}", accountId);
        
        try {
            List<Transaction> transactions = transactionService.getStampedTransactionsForCurrentYear(accountId);
            String csvContent = generateCsv(transactions);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("text/csv"));
            headers.setContentDispositionFormData("attachment", "transactions-" + accountId + ".csv");
            
            return ResponseEntity.ok()
                .headers(headers)
                .body(csvContent);
                
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for CSV export: {}", e.getMessage());
            return ResponseEntity.badRequest().body("error,message\n\"400\",\"" + e.getMessage() + "\"");
        } catch (Exception e) {
            log.error("Error generating CSV for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body("error,message\n\"500\",\"Internal Server Error\"");
        }
    }
    
    private String generateCsv(List<Transaction> transactions) {
        try (StringWriter writer = new StringWriter();
             CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.Builder.create()
                 .setHeader("sequence", "created", "type", "amount", "currency", 
                           "transactedAt", "beneficiaryIBAN", "originatingCountry", 
                           "paymentRef", "purposeRef")
                 .build())) {
            
            // Date formatter for created timestamp (DD-MMM-YY HH:mm:ss format as shown in plan)
            DateTimeFormatter createdFormatter = DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss");
            DateTimeFormatter transactedFormatter = DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss");
            
            for (Transaction transaction : transactions) {
                csvPrinter.printRecord(
                    transaction.getSequence(),
                    transaction.getCreatedAt().atZone(java.time.ZoneOffset.UTC).format(createdFormatter),
                    transaction.getTransactionType().toString().toLowerCase(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    transaction.getTransactedAt().atZone(java.time.ZoneOffset.UTC).format(transactedFormatter),
                    transaction.getBeneficiaryIBAN() != null ? transaction.getBeneficiaryIBAN() : "",
                    transaction.getOriginatingCountry() != null ? transaction.getOriginatingCountry() : "",
                    transaction.getPaymentRef() != null ? transaction.getPaymentRef() : "",
                    transaction.getPurposeRef() != null ? transaction.getPurposeRef() : ""
                );
            }
            
            csvPrinter.flush();
            return writer.toString();
            
        } catch (IOException e) {
            log.error("Error generating CSV: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate CSV", e);
        }
    }
} 
