package com.fintechdemo.workflow.lambda;

import com.fintechdemo.workflow.BaseIntegrationTest;
import com.fintechdemo.workflow.model.Account;
import com.fintechdemo.workflow.model.Transaction;
import com.fintechdemo.workflow.service.TransactionService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import javax.inject.Inject;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.*;

@Slf4j
class DynamoDbStreamHandlerIT extends BaseIntegrationTest {

    @Inject
    private TransactionService transactionService;
    
    @Inject
    private DynamoDbClient dynamoDbClient;

    @Test
    void shouldProcessPendingTransactionAndUpdateAccount() {
        // Given: Create a customer and account
        String customerId = java.util.UUID.randomUUID().toString();
        String accountId = java.util.UUID.randomUUID().toString();
        
        // Create account manually for testing
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        
        Account account = Account.builder()
            .id(java.util.UUID.fromString(accountId))
            .type("ACCOUNT")
            .customerId(customerId)
            .name("Test Account")
            .currency("EUR")
            .balance(BigDecimal.ZERO)
            .pending(BigDecimal.ZERO)
            .status(Account.AccountStatus.ACTIVE)
            .version(java.util.UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
            
        accountTable.putItem(account);
        
        // Create a pending transaction
        Transaction pendingTransaction = transactionService.createDeposit(
            accountId,
            "user123",
            "EUR", 
            new BigDecimal("1000.50"),
            Instant.now(),
            "DE89370400440532013000",
            "DE",
            "Test deposit",
            "Testing stream processing"
        );
        
        log.info("Created pending transaction: {} with sequence: {}", 
                 pendingTransaction.getId(), pendingTransaction.getSequence());
        
        // Verify transaction has pending sequence
        assertThat(pendingTransaction.getSequence()).startsWith("pending-");
        
                 // When: Simulate stream processing
         DynamoDbStreamHandler streamHandler = new DynamoDbStreamHandler();
         streamHandler.setDynamoDbClient(dynamoDbClient);
         streamHandler.setTableName(tableName);
         streamHandler.processTransactionSequencing(pendingTransaction.getId(), accountId);
        
        // Then: Verify transaction has been updated with final sequence
        DynamoDbTable<Transaction> transactionTable = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        Transaction updatedTransaction = transactionTable.getItem(
            Key.builder().partitionValue(pendingTransaction.getId()).build()
        );
        
        assertThat(updatedTransaction).isNotNull();
        assertThat(updatedTransaction.getSequence()).startsWith("transaction-");
        assertThat(updatedTransaction.getSequence()).contains(getCurrentDateString());
        assertThat(updatedTransaction.getSequence()).endsWith("000001"); // First transaction of the day
        
        // Verify account has been updated with latest transaction
        Account updatedAccount = accountTable.getItem(
            Key.builder().partitionValue(accountId).build()
        );
        
        assertThat(updatedAccount).isNotNull();
        assertThat(updatedAccount.getLatestTransaction()).isEqualTo(updatedTransaction.getSequence());
        
        log.info("Successfully processed transaction. Final sequence: {}, Account latest: {}", 
                 updatedTransaction.getSequence(), updatedAccount.getLatestTransaction());
    }
    
    @Test
    void shouldGenerateSequentialTransactionNumbers() {
                 // Given: Create account
         String customerId = java.util.UUID.randomUUID().toString();
         String accountId = java.util.UUID.randomUUID().toString();
        
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        
        Account account = Account.builder()
            .id(java.util.UUID.fromString(accountId))
            .type("ACCOUNT")
            .customerId(customerId)
            .name("Test Account")
            .currency("EUR")
            .balance(BigDecimal.ZERO)
            .pending(BigDecimal.ZERO)
            .status(Account.AccountStatus.ACTIVE)
            .version(java.util.UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
            
        accountTable.putItem(account);
        
        // Create multiple pending transactions
        Transaction tx1 = transactionService.createDeposit(accountId, "user1", "EUR", new BigDecimal("100"), 
                                                           Instant.now(), "DE89370400440532013000", "DE", "ref1", "purpose1");
        Transaction tx2 = transactionService.createWithdrawal(accountId, "user2", "USD", new BigDecimal("-50"), 
                                                              Instant.now(), "GB82WEST12345698765432", "US", "ref2", "purpose2");
        
                 // When: Process transactions in sequence
         DynamoDbStreamHandler streamHandler = new DynamoDbStreamHandler();
         streamHandler.setDynamoDbClient(dynamoDbClient);
         streamHandler.setTableName(tableName);
         streamHandler.processTransactionSequencing(tx1.getId(), accountId);
         streamHandler.processTransactionSequencing(tx2.getId(), accountId);
        
        // Then: Verify sequential numbering
        DynamoDbTable<Transaction> transactionTable = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        
        Transaction processedTx1 = transactionTable.getItem(Key.builder().partitionValue(tx1.getId()).build());
        Transaction processedTx2 = transactionTable.getItem(Key.builder().partitionValue(tx2.getId()).build());
        
        assertThat(processedTx1.getSequence()).endsWith("000001");
        assertThat(processedTx2.getSequence()).endsWith("000002");
        
        log.info("Transaction 1 sequence: {}", processedTx1.getSequence());
        log.info("Transaction 2 sequence: {}", processedTx2.getSequence());
    }
    
    @Test
    void shouldBeIdempotentForAlreadyProcessedTransactions() {
                 // Given: Create account and transaction
         String customerId = java.util.UUID.randomUUID().toString();
         String accountId = java.util.UUID.randomUUID().toString();
        
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        DynamoDbTable<Transaction> transactionTable = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        
        Account account = Account.builder()
            .id(java.util.UUID.fromString(accountId))
            .type("ACCOUNT")
            .customerId(customerId)
            .name("Test Account")
            .currency("EUR")
            .balance(BigDecimal.ZERO)
            .pending(BigDecimal.ZERO)
            .status(Account.AccountStatus.ACTIVE)
            .version(java.util.UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
            
        accountTable.putItem(account);
        
        Transaction pendingTransaction = transactionService.createDeposit(accountId, "user1", "EUR", new BigDecimal("100"), 
                                                                         Instant.now(), "DE89370400440532013000", "DE", "ref1", "purpose1");
        
                 // When: Process transaction twice
         DynamoDbStreamHandler streamHandler = new DynamoDbStreamHandler();
         streamHandler.setDynamoDbClient(dynamoDbClient);
         streamHandler.setTableName(tableName);
         streamHandler.processTransactionSequencing(pendingTransaction.getId(), accountId);
         
         Transaction afterFirstProcessing = transactionTable.getItem(Key.builder().partitionValue(pendingTransaction.getId()).build());
         String firstSequence = afterFirstProcessing.getSequence();
         
         // Process again (should be idempotent)
         streamHandler.processTransactionSequencing(pendingTransaction.getId(), accountId);
        
        // Then: Verify sequence hasn't changed
        Transaction afterSecondProcessing = transactionTable.getItem(Key.builder().partitionValue(pendingTransaction.getId()).build());
        
        assertThat(afterSecondProcessing.getSequence()).isEqualTo(firstSequence);
        assertThat(afterSecondProcessing.getSequence()).startsWith("transaction-");
        
        log.info("Idempotent processing verified. Sequence remained: {}", afterSecondProcessing.getSequence());
    }
    
    @Test
    void shouldHandleDayRolloverCorrectly() {
        // Given: Create account with a transaction from a previous day
        String customerId = java.util.UUID.randomUUID().toString();
        String accountId = java.util.UUID.randomUUID().toString();
        
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        
        // Simulate account with a transaction from yesterday
        String yesterdayDate = LocalDate.now(ZoneOffset.UTC).minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String previousDaySequence = String.format("transaction-%s-000005", yesterdayDate); // 5th transaction yesterday
        
        Account account = Account.builder()
            .id(java.util.UUID.fromString(accountId))
            .type("ACCOUNT")
            .customerId(customerId)
            .name("Test Account")
            .currency("EUR")
            .balance(BigDecimal.ZERO)
            .pending(BigDecimal.ZERO)
            .status(Account.AccountStatus.ACTIVE)
            .latestTransaction(previousDaySequence) // Set previous day's transaction
            .version(java.util.UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
            
        accountTable.putItem(account);
        
        // Create a new pending transaction for today
        Transaction pendingTransaction = transactionService.createDeposit(
            accountId,
            "user123",
            "EUR", 
            new BigDecimal("1000.50"),
            Instant.now(),
            "DE89370400440532013000",
            "DE",
            "Test deposit",
            "Testing day rollover"
        );
        
        // When: Process the transaction
        DynamoDbStreamHandler streamHandler = new DynamoDbStreamHandler();
        streamHandler.setDynamoDbClient(dynamoDbClient);
        streamHandler.setTableName(tableName);
        streamHandler.processTransactionSequencing(pendingTransaction.getId(), accountId);
        
        // Then: Verify sequence starts at 1 for the new day
        DynamoDbTable<Transaction> transactionTable = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        Transaction updatedTransaction = transactionTable.getItem(
            Key.builder().partitionValue(pendingTransaction.getId()).build()
        );
        
        assertThat(updatedTransaction).isNotNull();
        assertThat(updatedTransaction.getSequence()).startsWith("transaction-");
        assertThat(updatedTransaction.getSequence()).contains(getCurrentDateString());
        assertThat(updatedTransaction.getSequence()).endsWith("000001"); // Should reset to 1 for new day
        
        // Verify account has been updated with latest transaction
        Account updatedAccount = accountTable.getItem(
            Key.builder().partitionValue(accountId).build()
        );
        
        assertThat(updatedAccount).isNotNull();
        assertThat(updatedAccount.getLatestTransaction()).isEqualTo(updatedTransaction.getSequence());
        assertThat(updatedAccount.getLatestTransaction()).isNotEqualTo(previousDaySequence);
        
        log.info("Day rollover test: Previous sequence: {}, New sequence: {}", 
                 previousDaySequence, updatedTransaction.getSequence());
    }

    @Test
    void shouldUpdateAccountBalancesCorrectly() {
        // Given: Create account with initial balance
        String customerId = java.util.UUID.randomUUID().toString();
        String accountId = java.util.UUID.randomUUID().toString();
        
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Account> accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));
        
        Account account = Account.builder()
            .id(java.util.UUID.fromString(accountId))
            .type("ACCOUNT")
            .customerId(customerId)
            .name("Test Account")
            .currency("EUR")
            .balance(BigDecimal.valueOf(1000)) // Starting with 1000
            .pending(BigDecimal.ZERO) // Starting with 0 pending
            .status(Account.AccountStatus.ACTIVE)
            .version(java.util.UUID.randomUUID())
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
            
        accountTable.putItem(account);
        
        // Create a deposit transaction
        Transaction depositTransaction = transactionService.createDeposit(
            accountId,
            "user123",
            "EUR", 
            new BigDecimal("500.00"),
            Instant.now(),
            "DE89370400440532013000",
            "DE",
            "Test deposit",
            "Testing balance update"
        );
        
        // Create a withdrawal transaction
        Transaction withdrawalTransaction = transactionService.createWithdrawal(
            accountId,
            "user456",
            "EUR", 
            new BigDecimal("-200.00"),
            Instant.now(),
            "GB82WEST12345698765432",
            "US",
            "Test withdrawal",
            "Testing balance update"
        );
        
        // When: Process both transactions
        DynamoDbStreamHandler streamHandler = new DynamoDbStreamHandler();
        streamHandler.setDynamoDbClient(dynamoDbClient);
        streamHandler.setTableName(tableName);
        
        streamHandler.processTransactionSequencing(depositTransaction.getId(), accountId);
        streamHandler.processTransactionSequencing(withdrawalTransaction.getId(), accountId);
        
        // Then: Verify account balances are updated correctly
        Account updatedAccount = accountTable.getItem(
            Key.builder().partitionValue(accountId).build()
        );
        
        assertThat(updatedAccount).isNotNull();
        // Deposit should increase balance: 1000 + 500 = 1500
        assertThat(updatedAccount.getBalance().compareTo(new BigDecimal("1500.00"))).isEqualTo(0);
        // Withdrawal should only affect pending: 0 + 200 = 200
        assertThat(updatedAccount.getPending().compareTo(new BigDecimal("200.00"))).isEqualTo(0);
        
        log.info("Balance update test successful. Final balance: {}, Pending: {}", 
                 updatedAccount.getBalance(), updatedAccount.getPending());
    }

    private String getCurrentDateString() {
        return LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }
} 
