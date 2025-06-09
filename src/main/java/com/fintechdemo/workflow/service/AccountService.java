package com.fintechdemo.workflow.service;

import com.fintechdemo.workflow.model.Account;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedReorderedGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountService {
    
    private final DynamoDbClient dynamoDbClient;
    
    @Value("${app.dynamodb.table-name:fintechdemo-workflow-dev}")
    private String tableName;
    
    // UUIDv7 generator for time-ordered sequence values
    private static final TimeBasedReorderedGenerator UUID_V7_GENERATOR = Generators.timeBasedReorderedGenerator();
    
    public Account createAccount(String customerId, String name, String currency) {
        log.info("Creating account for customer: {}, name: {}, currency: {}", customerId, name, currency);
        
        // Validate input
        if (customerId == null || customerId.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer ID cannot be null or empty");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Account name cannot be null or empty");
        }
        if (currency == null || currency.trim().isEmpty()) {
            throw new IllegalArgumentException("Currency cannot be null or empty");
        }
        
        // Generate UUIDv4 for new account
        UUID accountId = UUID.randomUUID();
        // Generate UUIDv7 for time-ordered sequence
        UUID sequenceUuid = UUID_V7_GENERATOR.generate();
        // Generate UUIDv7 for version (optimistic concurrency control)
        UUID versionId = UUID_V7_GENERATOR.generate();
        Instant now = Instant.now();
        
        Account account = Account.builder()
            .id(accountId)
            .type(Account.ENTITY_TYPE)
            .parent(customerId) // Set customer as parent for GSI
            .sequence("account-" + sequenceUuid.toString()) // Use UUIDv7 for time-ordered sequence
            .customerId(customerId)
            .name(name.trim())
            .accountNumber("ACC" + System.currentTimeMillis())
            .currency(currency.toUpperCase().trim())
            .balance(BigDecimal.ZERO)
            .pending(BigDecimal.ZERO)
            .status(Account.AccountStatus.ACTIVE) // Set to ACTIVE as requested
            .version(versionId) // Set UUIDv7 version for optimistic concurrency control
            .createdAt(now)
            .updatedAt(now)
            .build();

        // Save to DynamoDB
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> table = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        
        try {
            table.putItem(account);
            log.info("Successfully created account with ID: {}", accountId);
            return account;
        } catch (Exception e) {
            log.error("Failed to create account: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create account", e);
        }
    }

    public Account findById(String id) {
        log.info("Finding account with ID: {}", id);
        
        if (id == null || id.trim().isEmpty()) {
            log.warn("Invalid account ID provided: {}", id);
            return null;
        }
        
        // Retrieve from DynamoDB
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> table = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        
        try {
            Key key = Key.builder()
                .partitionValue(id)
                .build();
                
            Account account = table.getItem(key);
            
            if (account == null) {
                log.info("Account not found with ID: {}", id);
                return null;
            }
            
            log.info("Successfully found account: {}", account.getName());
            return account;
        } catch (Exception e) {
            log.error("Failed to find account with ID {}: {}", id, e.getMessage(), e);
            return null;
        }
    }

    public Account getAccount(String id) {
        // Keep this method for backward compatibility
        return findById(id);
    }

    public List<Account> getCustomerAccounts(String customerId) {
        log.info("Finding accounts for customer: {}", customerId);
        
        if (customerId == null || customerId.trim().isEmpty()) {
            log.warn("Invalid customer ID provided: {}", customerId);
            return List.of();
        }
        
        // Query using GSI to find accounts by parent (customer ID)
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> table = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        DynamoDbIndex<Account> parentIndex = table.index("parent-sequence-index");
        
        try {
            // Query with sequence prefix filter to only get accounts (not other child entities)
            QueryConditional queryConditional = QueryConditional.sortBeginsWith(
                Key.builder()
                    .partitionValue(customerId)
                    .sortValue("account-")
                    .build()
            );
            
            List<Account> accounts = parentIndex.query(queryConditional)
                .stream()
                .flatMap(page -> page.items().stream())
                .collect(Collectors.toList());
                
            log.info("Found {} accounts for customer: {}", accounts.size(), customerId);
            return accounts;
        } catch (Exception e) {
            log.error("Failed to find accounts for customer {}: {}", customerId, e.getMessage(), e);
            return List.of();
        }
    }
} 
