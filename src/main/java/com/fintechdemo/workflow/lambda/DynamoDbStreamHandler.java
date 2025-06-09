package com.fintechdemo.workflow.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import com.fintechdemo.workflow.model.Account;
import com.fintechdemo.workflow.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.math.BigDecimal;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedReorderedGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Slf4j
public class DynamoDbStreamHandler implements RequestHandler<DynamodbEvent, String> {

    private DynamoDbClient dynamoDbClient;
    private String tableName;
    
    // UUIDv7 generator for version fields
    private static final TimeBasedReorderedGenerator UUID_V7_GENERATOR = Generators.timeBasedReorderedGenerator();
    
    // Date formatter for sequence generation (YYYYMMDD format)
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Override
    public String handleRequest(DynamodbEvent event, Context context) {
        try {
            log.info("Stream handler invoked with {} records", event.getRecords().size());
            
            // Initialize DynamoDB client if not already done
            if (dynamoDbClient == null) {
                log.info("Initializing DynamoDB client...");
                initializeSpringContext();
                log.info("DynamoDB client initialized successfully");
            }

            log.info("Processing DynamoDB stream event with {} records", event.getRecords().size());

            for (DynamodbEvent.DynamodbStreamRecord record : event.getRecords()) {
                log.info("Processing record: eventName={}, eventSource={}", 
                         record.getEventName(), record.getEventSource());
                processRecord(record);
            }
            
            log.info("Successfully processed all {} records", event.getRecords().size());
            return "SUCCESS";
        } catch (Exception e) {
            log.error("Failed to process DynamoDB stream event: {}", e.getMessage(), e);
            e.printStackTrace(); // Print full stack trace to CloudWatch
            // Throw exception to trigger retry mechanism
            throw new RuntimeException("Stream processing failed: " + e.getMessage(), e);
        }
    }

    private void initializeSpringContext() {
        log.info("Initializing DynamoDB client for stream handler");
        // Skip Spring Boot initialization for Lambda stream handler
        // Initialize DynamoDB client directly
        dynamoDbClient = DynamoDbClient.builder()
            .region(software.amazon.awssdk.regions.Region.EU_WEST_1)
            .build();
        tableName = System.getenv("WORKFLOW_TABLE");
        if (tableName == null) {
            tableName = "fintechdemo-workflow-dev";
        }
        log.info("Initialized with table name: {}", tableName);
    }

    private void processRecord(DynamodbEvent.DynamodbStreamRecord record) {
        try {
            log.info("Processing record with eventName: {}", record.getEventName());
            
            // Only process INSERT events (new transactions)
            if (!"INSERT".equals(record.getEventName())) {
                log.info("Skipping non-INSERT event: {}", record.getEventName());
                return;
            }

            // Extract the new image
            java.util.Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
            if (newImage == null) {
                log.warn("No new image in record, skipping");
                return;
            }

            log.info("Record has {} attributes", newImage.size());

            // Check if this is a transaction with pending sequence
            AttributeValue typeAttr = newImage.get("type");
            AttributeValue sequenceAttr = newImage.get("sequence");
            
            if (typeAttr == null) {
                log.info("No 'type' attribute found, skipping");
                return;
            }
            
            if (!"TRANSACTION".equals(typeAttr.getS())) {
                log.info("Not a TRANSACTION type ({}), skipping", typeAttr.getS());
                return;
            }
            
            if (sequenceAttr == null) {
                log.warn("No 'sequence' attribute found for transaction, skipping");
                return;
            }
            
            if (!sequenceAttr.getS().startsWith("pending-")) {
                log.info("Transaction sequence does not start with 'pending-' ({}), skipping", sequenceAttr.getS());
                return;
            }

            String transactionId = newImage.get("id").getS();
            String accountId = newImage.get("accountId").getS();
            
            log.info("Processing pending transaction: {} for account: {}", transactionId, accountId);
            
            processTransactionSequencing(transactionId, accountId);
            
        } catch (Exception e) {
            log.error("Failed to process record: {}", e.getMessage(), e);
            e.printStackTrace();
            throw e; // Re-throw to trigger retry
        }
    }

    void processTransactionSequencing(String transactionId, String accountId) {
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();

        DynamoDbTable<Transaction> transactionTable = enhancedClient.table(tableName, TableSchema.fromBean(Transaction.class));
        DynamoDbTable<Account> accountTable = enhancedClient.table(tableName, TableSchema.fromBean(Account.class));

        // Fetch the transaction
        Transaction transaction = transactionTable.getItem(Key.builder().partitionValue(transactionId).build());
        if (transaction == null) {
            log.warn("Transaction {} not found, skipping", transactionId);
            return;
        }

        // Check if transaction already has a final sequence (idempotency)
        if (!transaction.getSequence().startsWith("pending-")) {
            log.info("Transaction {} already has final sequence: {}, skipping", transactionId, transaction.getSequence());
            return;
        }

        // Fetch the account
        Account account = accountTable.getItem(Key.builder().partitionValue(accountId).build());
        if (account == null) {
            log.error("Account {} not found for transaction {}", accountId, transactionId);
            throw new RuntimeException("Account not found: " + accountId);
        }

        // Generate the next sequence number
        String newSequence = generateNextSequence(account.getLatestTransaction());
        
        log.info("Assigning sequence {} to transaction {} for account {}", newSequence, transactionId, accountId);

        // Calculate updated account balances based on transaction type
        BigDecimal newBalance = account.getBalance();
        BigDecimal newPending = account.getPending();
        
        if (transaction.getTransactionType() == Transaction.TransactionType.DEPOSIT) {
            // Deposits only update balance (positive amounts)
            newBalance = newBalance.add(transaction.getAmount());
            log.info("Deposit: Adding {} to account balance. New balance: {}, Pending: {}", 
                     transaction.getAmount(), newBalance, newPending);
        } else if (transaction.getTransactionType() == Transaction.TransactionType.WITHDRAWAL) {
            // Withdrawals only update pending (negative amounts, so pending grows)
            newPending = newPending.add(transaction.getAmount().abs());
            log.info("Withdrawal: Adding {} to pending. Balance: {}, New pending: {}", 
                     transaction.getAmount().abs(), newBalance, newPending);
        }

        // Prepare updated entities with new versions for optimistic locking
        UUID originalTransactionVersion = transaction.getVersion();
        UUID originalAccountVersion = account.getVersion();
        UUID transactionVersion = UUID_V7_GENERATOR.generate();
        UUID accountVersion = UUID_V7_GENERATOR.generate();
        Instant now = Instant.now();

        Transaction updatedTransaction = Transaction.builder()
            .id(transaction.getUuid())
            .type(transaction.getType())
            .parent(transaction.getParent())
            .sequence(newSequence)
            .version(transactionVersion)
            .createdAt(transaction.getCreatedAt())
            .updatedAt(now)
            .accountId(transaction.getAccountId())
            .userId(transaction.getUserId())
            .currency(transaction.getCurrency())
            .amount(transaction.getAmount())
            .transactedAt(transaction.getTransactedAt())
            .beneficiaryIBAN(transaction.getBeneficiaryIBAN())
            .payorIBAN(transaction.getPayorIBAN())
            .originatingCountry(transaction.getOriginatingCountry())
            .paymentRef(transaction.getPaymentRef())
            .purposeRef(transaction.getPurposeRef())
            .transactionType(transaction.getTransactionType())
            .build();

        Account updatedAccount = Account.builder()
            .id(account.getUuid())
            .type(account.getType())
            .parent(account.getParent())
            .sequence(account.getSequence())
            .version(accountVersion)
            .createdAt(account.getCreatedAt())
            .updatedAt(now)
            .customerId(account.getCustomerId())
            .name(account.getName())
            .accountNumber(account.getAccountNumber())
            .currency(account.getCurrency())
            .balance(newBalance)
            .pending(newPending)
            .status(account.getStatus())
            .latestTransaction(newSequence)
            .build();

        // Create conditional expressions for optimistic locking
        Expression transactionCondition = Expression.builder()
            .expression("#version = :expectedTransactionVersion")
            .putExpressionName("#version", "version")
            .putExpressionValue(":expectedTransactionVersion", 
                software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                    .s(originalTransactionVersion.toString())
                    .build())
            .build();
            
        Expression accountCondition = Expression.builder()
            .expression("#version = :expectedAccountVersion")
            .putExpressionName("#version", "version")
            .putExpressionValue(":expectedAccountVersion", 
                software.amazon.awssdk.services.dynamodb.model.AttributeValue.builder()
                    .s(originalAccountVersion.toString())
                    .build())
            .build();

        // Create put requests with conditional expressions for optimistic locking
        PutItemEnhancedRequest<Transaction> transactionPutRequest = PutItemEnhancedRequest.builder(Transaction.class)
            .item(updatedTransaction)
            .conditionExpression(transactionCondition)
            .build();
            
        PutItemEnhancedRequest<Account> accountPutRequest = PutItemEnhancedRequest.builder(Account.class)
            .item(updatedAccount)
            .conditionExpression(accountCondition)
            .build();

        // Perform transactional write with optimistic locking
        try {
            enhancedClient.transactWriteItems(TransactWriteItemsEnhancedRequest.builder()
                .addPutItem(transactionTable, transactionPutRequest)
                .addPutItem(accountTable, accountPutRequest)
                .build());
            
            log.info("✅ TRANSACTION STAMPED SUCCESSFULLY: Transaction {} assigned sequence {} for account {}", 
                     transactionId, newSequence, accountId);
            log.info("📊 Account {} balance updated: {} -> {}, pending: {} -> {}", 
                     accountId, account.getBalance(), newBalance, account.getPending(), newPending);
            log.info("🎯 Stream processing completed for {} transaction of {} {}", 
                     transaction.getTransactionType().toString().toLowerCase(), 
                     transaction.getAmount(), transaction.getCurrency());
            
        } catch (ConditionalCheckFailedException | TransactionCanceledException e) {
            log.warn("Optimistic lock failed for transaction {} (version {}) or account {} (version {}), will retry: {}", 
                     transactionId, originalTransactionVersion, accountId, originalAccountVersion, e.getMessage());
            throw new RuntimeException("Optimistic lock failure", e);
        }
    }

    private String generateNextSequence(String currentLatestTransaction) {
        String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FORMATTER);
        
        if (currentLatestTransaction == null) {
            // First transaction ever for this account
            log.info("No previous transaction found, starting with sequence 1 for date {}", today);
            return String.format("transaction-%s-%06d", today, 1);
        }
        
        // Check if latest transaction is from today
        if (!currentLatestTransaction.startsWith("transaction-" + today)) {
            // Latest transaction is from a previous day, start fresh at 1
            log.info("Latest transaction {} is from previous day, starting with sequence 1 for date {}", 
                     currentLatestTransaction, today);
            return String.format("transaction-%s-%06d", today, 1);
        }
        
        // Extract the sequence number from the latest transaction (same day)
        try {
            String[] parts = currentLatestTransaction.split("-");
            if (parts.length >= 3) {
                int lastSequence = Integer.parseInt(parts[2]);
                int nextSequence = lastSequence + 1;
                log.info("Latest transaction {} has sequence {}, generating next sequence {} for date {}", 
                         currentLatestTransaction, lastSequence, nextSequence, today);
                return String.format("transaction-%s-%06d", today, nextSequence);
            }
        } catch (NumberFormatException e) {
            log.error("Could not parse sequence number from latest transaction: {}", currentLatestTransaction, e);
            throw new RuntimeException("Invalid latest transaction sequence format: " + currentLatestTransaction, e);
        }
        
        // If we can't parse the sequence, this is an error condition
        log.error("Could not parse latest transaction format: {}", currentLatestTransaction);
        throw new RuntimeException("Invalid latest transaction format: " + currentLatestTransaction);
    }
    
    // Package-private setters for testing
    void setDynamoDbClient(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }
    
    void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public static void main(String[] args) {
        log.info("DynamoDB Stream Handler started");
    }
} 
