package com.fintechdemo.workflow.service;

import com.fintechdemo.workflow.controller.TransactionListResponse;
import com.fintechdemo.workflow.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedReorderedGenerator;
import java.time.LocalDate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionService {
    
    private final DynamoDbClient dynamoDbClient;
    
    @Value("${app.dynamodb.table-name:fintechdemo-workflow-dev}")
    private String tableName;
    
    // UUIDv7 generator for sequence and version fields
    private static final TimeBasedReorderedGenerator UUID_V7_GENERATOR = Generators.timeBasedReorderedGenerator();
    
    // Simple IBAN validation pattern (basic format check)
    private static final Pattern IBAN_PATTERN = Pattern.compile("^[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}([A-Z0-9]?){0,16}$");
    
    public Transaction createDeposit(String accountId, String userId, String currency, 
                                   BigDecimal amount, Instant transactedAt, String payorIBAN,
                                   String originatingCountry, String paymentRef, String purposeRef) {
        log.info("Creating deposit for account: {}, amount: {} {}", accountId, amount, currency);
        
        validateCommonFields(accountId, userId, currency, amount, transactedAt, Transaction.TransactionType.DEPOSIT);
        
        // Validate payor IBAN if provided
        if (payorIBAN != null && !payorIBAN.trim().isEmpty() && !isValidIBAN(payorIBAN)) {
            throw new IllegalArgumentException("Invalid payor IBAN: " + payorIBAN);
        }
        
        return createTransaction(accountId, userId, currency, amount, transactedAt, 
                               null, payorIBAN, originatingCountry, paymentRef, purposeRef, 
                               Transaction.TransactionType.DEPOSIT);
    }
    
    public Transaction createWithdrawal(String accountId, String userId, String currency, 
                                      BigDecimal amount, Instant transactedAt, String beneficiaryIBAN,
                                      String originatingCountry, String paymentRef, String purposeRef) {
        log.info("Creating withdrawal for account: {}, amount: {} {}", accountId, amount, currency);
        
        // Validate that withdrawal amounts are negative
        if (amount == null || amount.compareTo(BigDecimal.ZERO) >= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be negative");
        }
        
        validateCommonFields(accountId, userId, currency, amount, transactedAt, Transaction.TransactionType.WITHDRAWAL);
        
        // Validate beneficiary IBAN (required for withdrawals)
        if (beneficiaryIBAN == null || beneficiaryIBAN.trim().isEmpty()) {
            throw new IllegalArgumentException("Beneficiary IBAN is required for withdrawals");
        }
        if (!isValidIBAN(beneficiaryIBAN)) {
            throw new IllegalArgumentException("Invalid beneficiary IBAN: " + beneficiaryIBAN);
        }
        
        return createTransaction(accountId, userId, currency, amount, transactedAt, 
                               beneficiaryIBAN, null, originatingCountry, paymentRef, purposeRef, 
                               Transaction.TransactionType.WITHDRAWAL);
    }
    
    public TransactionListResponse getAccountTransactions(String accountId, String nextToken, Integer limit) {
        log.info("Finding transactions for account: {}", accountId);
        
        if (accountId == null || accountId.trim().isEmpty()) {
            log.warn("Invalid account ID provided: {}", accountId);
            return new TransactionListResponse(List.of(), null);
        }
        
        if (limit == null || limit <= 0) {
            limit = 20; // Default page size
        }
        
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Transaction> table = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        DynamoDbIndex<Transaction> parentIndex = table.index("parent-sequence-index");
        
        try {
            List<Transaction> allTransactions = new ArrayList<>();
            String newNextToken = null;
            
            // First, get all pending transactions (assume they fit in one page)
            List<Transaction> pendingTransactions = getPendingTransactions(parentIndex, accountId);
            allTransactions.addAll(pendingTransactions);
            
            // Then get other transactions with pagination
            if (nextToken == null) {
                // If no next token, start from the beginning of non-pending transactions
                List<Transaction> otherTransactions = getOtherTransactions(parentIndex, accountId, limit - pendingTransactions.size());
                allTransactions.addAll(otherTransactions);
                
                // Set next token if we got a full page of other transactions
                if (otherTransactions.size() == (limit - pendingTransactions.size()) && !otherTransactions.isEmpty()) {
                    Transaction lastTransaction = otherTransactions.get(otherTransactions.size() - 1);
                    newNextToken = lastTransaction.getSequence();
                }
            } else {
                // Continue from where we left off
                List<Transaction> otherTransactions = getOtherTransactionsFromToken(parentIndex, accountId, nextToken, limit);
                allTransactions.addAll(otherTransactions);
                
                // Set next token if we got a full page
                if (otherTransactions.size() == limit && !otherTransactions.isEmpty()) {
                    Transaction lastTransaction = otherTransactions.get(otherTransactions.size() - 1);
                    newNextToken = lastTransaction.getSequence();
                }
            }
            
            log.info("Found {} transactions for account: {}", allTransactions.size(), accountId);
            return new TransactionListResponse(allTransactions, newNextToken);
        } catch (Exception e) {
            log.error("Failed to find transactions for account {}: {}", accountId, e.getMessage(), e);
            return new TransactionListResponse(List.of(), null);
        }
    }
    
    private void validateCommonFields(String accountId, String userId, String currency, BigDecimal amount, Instant transactedAt, Transaction.TransactionType type) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID cannot be null or empty");
        }
        if (currency == null || currency.trim().isEmpty()) {
            throw new IllegalArgumentException("Currency cannot be null or empty");
        }
        if (amount == null) {
            throw new IllegalArgumentException("Amount cannot be null");
        }
        
        // Validate amount based on transaction type
        if (type == Transaction.TransactionType.DEPOSIT && amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Deposit amount must be positive");
        }
        if (type == Transaction.TransactionType.WITHDRAWAL && amount.compareTo(BigDecimal.ZERO) >= 0) {
            throw new IllegalArgumentException("Withdrawal amount must be negative");
        }
        
        if (transactedAt == null) {
            throw new IllegalArgumentException("Transaction date cannot be null");
        }
    }
    
    private Transaction createTransaction(String accountId, String userId, String currency, 
                                        BigDecimal amount, Instant transactedAt, String beneficiaryIBAN,
                                        String payorIBAN, String originatingCountry, String paymentRef, 
                                        String purposeRef, Transaction.TransactionType type) {
        
        // Generate UUIDv4 for new transaction
        UUID transactionId = UUID.randomUUID();
        // Generate UUIDv7 for pending sequence
        UUID sequenceUuid = UUID_V7_GENERATOR.generate();
        // Generate UUIDv7 for version
        UUID versionId = UUID_V7_GENERATOR.generate();
        Instant now = Instant.now();
        
        Transaction transaction = Transaction.builder()
            .id(transactionId)
            .type(Transaction.ENTITY_TYPE)
            .parent(accountId) // Set account as parent for GSI
            .sequence("pending-" + sequenceUuid.toString()) // Use UUIDv7 for time-ordered pending sequence
            .accountId(accountId)
            .userId(userId)
            .currency(currency.toUpperCase().trim())
            .amount(amount)
            .transactedAt(transactedAt)
            .beneficiaryIBAN(beneficiaryIBAN)
            .payorIBAN(payorIBAN)
            .originatingCountry(originatingCountry)
            .paymentRef(paymentRef)
            .purposeRef(purposeRef)
            .transactionType(type)
            .version(versionId)
            .createdAt(now)
            .updatedAt(now)
            .build();

        // Save to DynamoDB
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Transaction> table = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        
        try {
            table.putItem(transaction);
            log.info("Successfully created {} transaction with ID: {}", type, transactionId);
            return transaction;
        } catch (Exception e) {
            log.error("Failed to create {} transaction: {}", type, e.getMessage(), e);
            throw new RuntimeException("Failed to create " + type + " transaction", e);
        }
    }
    
    private List<Transaction> getPendingTransactions(DynamoDbIndex<Transaction> parentIndex, String accountId) {
        QueryConditional queryConditional = QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(accountId)
                .sortValue("pending-")
                .build()
        );
        
        return parentIndex.query(queryConditional)
            .stream()
            .flatMap(page -> page.items().stream())
            .collect(Collectors.toList());
    }
    
    private List<Transaction> getOtherTransactions(DynamoDbIndex<Transaction> parentIndex, String accountId, int limit) {
        QueryConditional queryConditional = QueryConditional.sortBeginsWith(
            Key.builder()
                .partitionValue(accountId)
                .sortValue("transaction-")
                .build()
        );
        
        QueryEnhancedRequest queryRequest = QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .limit(limit)
            .build();
        
        return parentIndex.query(queryRequest)
            .stream()
            .flatMap(page -> page.items().stream())
            .collect(Collectors.toList());
    }
    
    private List<Transaction> getOtherTransactionsFromToken(DynamoDbIndex<Transaction> parentIndex, String accountId, String nextToken, int limit) {
        QueryConditional queryConditional = QueryConditional.sortGreaterThan(
            Key.builder()
                .partitionValue(accountId)
                .sortValue(nextToken)
                .build()
        );
        
        QueryEnhancedRequest queryRequest = QueryEnhancedRequest.builder()
            .queryConditional(queryConditional)
            .limit(limit)
            .build();
        
        return parentIndex.query(queryRequest)
            .stream()
            .flatMap(page -> page.items().stream())
            .filter(tx -> tx.getSequence().startsWith("transaction-")) // Only get transaction- sequences
            .collect(Collectors.toList());
    }
    
    /**
     * Get all stamped transactions for an account for the current year (for CSV export)
     */
    public List<Transaction> getStampedTransactionsForCurrentYear(String accountId) {
        log.info("Finding stamped transactions for current year for account: {}", accountId);
        
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }
        
        // Get current year in YYYYMMDD format for transaction sequence filtering
        String currentYear = String.valueOf(LocalDate.now().getYear());
        String transactionPrefix = "transaction-" + currentYear;
        
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();

        DynamoDbTable<Transaction> table = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        DynamoDbIndex<Transaction> gsi = table.index("parent-sequence-index");

        // Query the GSI to find all transactions for this account with current year sequences
        QueryConditional queryConditional = QueryConditional
            .sortBeginsWith(Key.builder()
                .partitionValue(accountId)
                .sortValue(transactionPrefix)
                .build());

        List<Transaction> transactions = gsi.query(queryConditional)
            .stream()
            .flatMap(page -> page.items().stream())
            .filter(tx -> tx.getSequence().startsWith(transactionPrefix)) // Additional safety filter
            .sorted((a, b) -> a.getSequence().compareTo(b.getSequence())) // Sort by sequence for chronological order
            .collect(Collectors.toList());

        log.info("Found {} stamped transactions for current year for account: {}", transactions.size(), accountId);
        return transactions;
    }

    private boolean isValidIBAN(String iban) {
        if (iban == null || iban.trim().isEmpty()) {
            return false;
        }
        // Remove spaces and convert to uppercase
        String cleanIban = iban.replaceAll("\\s", "").toUpperCase();
        // Basic format validation
        return IBAN_PATTERN.matcher(cleanIban).matches();
    }
} 
