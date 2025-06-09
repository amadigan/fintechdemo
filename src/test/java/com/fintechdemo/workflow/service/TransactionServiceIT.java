package com.fintechdemo.workflow.service;

import com.fintechdemo.workflow.BaseIntegrationTest;
import com.fintechdemo.workflow.controller.TransactionListResponse;
import com.fintechdemo.workflow.model.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import javax.inject.Inject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import com.fasterxml.uuid.Generators;

import static org.assertj.core.api.Assertions.*;

@Slf4j
class TransactionServiceIT extends BaseIntegrationTest {

    @Inject
    private TransactionService transactionService;

    @Test
    void shouldCreateDeposit() {
        // Given
        String accountId = UUID.randomUUID().toString();
        String userId = "user123";
        String currency = "EUR";
        BigDecimal amount = new BigDecimal("1000.50");
        Instant transactedAt = Instant.now().minusSeconds(3600);
        String payorIBAN = "DE89370400440532013000";
        String originatingCountry = "DE";
        String paymentRef = "Invoice payment";
        String purposeRef = "Business transaction";

        // When
        Transaction deposit = transactionService.createDeposit(
            accountId, userId, currency, amount, transactedAt,
            payorIBAN, originatingCountry, paymentRef, purposeRef
        );

        // Then
        assertThat(deposit).isNotNull();
        assertThat(deposit.getId()).isNotNull();
        assertThat(deposit.getAccountId()).isEqualTo(accountId);
        assertThat(deposit.getUserId()).isEqualTo(userId);
        assertThat(deposit.getCurrency()).isEqualTo(currency);
        assertThat(deposit.getAmount()).isEqualTo(amount);
        assertThat(deposit.getTransactedAt()).isEqualTo(transactedAt);
        assertThat(deposit.getPayorIBAN()).isEqualTo(payorIBAN);
        assertThat(deposit.getBeneficiaryIBAN()).isNull(); // No beneficiary for deposits
        assertThat(deposit.getOriginatingCountry()).isEqualTo(originatingCountry);
        assertThat(deposit.getPaymentRef()).isEqualTo(paymentRef);
        assertThat(deposit.getPurposeRef()).isEqualTo(purposeRef);
        assertThat(deposit.getTransactionType()).isEqualTo(Transaction.TransactionType.DEPOSIT);
        assertThat(deposit.getType()).isEqualTo("TRANSACTION");
        assertThat(deposit.getParent()).isEqualTo(accountId);
        assertThat(deposit.getSequence()).startsWith("pending-");
        assertThat(deposit.getVersionUuid()).isNotNull();
        assertThat(deposit.getCreatedAt()).isNotNull();
        assertThat(deposit.getUpdatedAt()).isNotNull();

        log.info("Created deposit: {}", deposit);
    }

    @Test
    void shouldCreateWithdrawal() {
        // Given
        String accountId = UUID.randomUUID().toString();
        String userId = "user456";
        String currency = "USD";
        BigDecimal amount = new BigDecimal("-500.75");
        Instant transactedAt = Instant.now().minusSeconds(1800);
        String beneficiaryIBAN = "GB82WEST12345698765432";
        String originatingCountry = "US";
        String paymentRef = "Rent payment";
        String purposeRef = "Monthly rent";

        // When
        Transaction withdrawal = transactionService.createWithdrawal(
            accountId, userId, currency, amount, transactedAt,
            beneficiaryIBAN, originatingCountry, paymentRef, purposeRef
        );

        // Then
        assertThat(withdrawal).isNotNull();
        assertThat(withdrawal.getId()).isNotNull();
        assertThat(withdrawal.getAccountId()).isEqualTo(accountId);
        assertThat(withdrawal.getUserId()).isEqualTo(userId);
        assertThat(withdrawal.getCurrency()).isEqualTo(currency);
        assertThat(withdrawal.getAmount()).isEqualTo(amount);
        assertThat(withdrawal.getTransactedAt()).isEqualTo(transactedAt);
        assertThat(withdrawal.getBeneficiaryIBAN()).isEqualTo(beneficiaryIBAN);
        assertThat(withdrawal.getPayorIBAN()).isNull(); // No payor for withdrawals
        assertThat(withdrawal.getOriginatingCountry()).isEqualTo(originatingCountry);
        assertThat(withdrawal.getPaymentRef()).isEqualTo(paymentRef);
        assertThat(withdrawal.getPurposeRef()).isEqualTo(purposeRef);
        assertThat(withdrawal.getTransactionType()).isEqualTo(Transaction.TransactionType.WITHDRAWAL);
        assertThat(withdrawal.getType()).isEqualTo("TRANSACTION");
        assertThat(withdrawal.getParent()).isEqualTo(accountId);
        assertThat(withdrawal.getSequence()).startsWith("pending-");
        assertThat(withdrawal.getVersionUuid()).isNotNull();
        assertThat(withdrawal.getCreatedAt()).isNotNull();
        assertThat(withdrawal.getUpdatedAt()).isNotNull();

        log.info("Created withdrawal: {}", withdrawal);
    }

    @Test
    void shouldValidateIBANForDeposits() {
        // Given
        String accountId = UUID.randomUUID().toString();
        String invalidIBAN = "INVALID_IBAN";

        // When & Then
        assertThatThrownBy(() -> transactionService.createDeposit(
            accountId, "user123", "EUR", new BigDecimal("100"),
            Instant.now(), invalidIBAN, "DE", "ref", "purpose"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid payor IBAN");
    }

    @Test
    void shouldValidateIBANForWithdrawals() {
        // Given
        String accountId = UUID.randomUUID().toString();
        String invalidIBAN = "INVALID_IBAN";

        // When & Then
        assertThatThrownBy(() -> transactionService.createWithdrawal(
            accountId, "user123", "EUR", new BigDecimal("-100"),
            Instant.now(), invalidIBAN, "DE", "ref", "purpose"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid beneficiary IBAN");
    }

    @Test
    void shouldRequireBeneficiaryIBANForWithdrawals() {
        // Given
        String accountId = UUID.randomUUID().toString();

        // When & Then - null IBAN
        assertThatThrownBy(() -> transactionService.createWithdrawal(
            accountId, "user123", "EUR", new BigDecimal("-100"),
            Instant.now(), null, "DE", "ref", "purpose"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Beneficiary IBAN is required");

        // When & Then - empty IBAN
        assertThatThrownBy(() -> transactionService.createWithdrawal(
            accountId, "user123", "EUR", new BigDecimal("-100"),
            Instant.now(), "", "DE", "ref", "purpose"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Beneficiary IBAN is required");
    }

    @Test
    void shouldRejectPositiveAmountsForWithdrawals() {
        // Given
        String accountId = UUID.randomUUID().toString();
        String validIBAN = "GB82WEST12345698765432";

        // When & Then - positive amount should be rejected
        assertThatThrownBy(() -> transactionService.createWithdrawal(
            accountId, "user123", "EUR", new BigDecimal("100"), // Positive amount
            Instant.now(), validIBAN, "DE", "ref", "purpose"
        ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Withdrawal amount must be negative");
    }

    @Test
    void shouldGetAccountTransactionsWithPendingOnly() {
        // Given
        String accountId = UUID.randomUUID().toString();
        String validIBAN = "DE89370400440532013000";

        // Create some transactions
        transactionService.createDeposit(accountId, "user1", "EUR", new BigDecimal("100"), Instant.now(), validIBAN, "DE", "ref1", "purpose1");
        transactionService.createWithdrawal(accountId, "user2", "USD", new BigDecimal("-50"), Instant.now(), "GB82WEST12345698765432", "US", "ref2", "purpose2");

        // When
        TransactionListResponse response = transactionService.getAccountTransactions(accountId, null, 10);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getTransactions()).hasSize(2);
        assertThat(response.getNextToken()).isNull(); // No pagination needed for pending transactions only

        // Verify all transactions are pending
        for (Transaction tx : response.getTransactions()) {
            assertThat(tx.getSequence()).startsWith("pending-");
            assertThat(tx.getAccountId()).isEqualTo(accountId);
        }

        log.info("Found {} transactions for account {}", response.getTransactions().size(), accountId);
    }

    @Test
    void shouldHandleEmptyTransactionList() {
        // Given
        String accountId = UUID.randomUUID().toString();

        // When
        TransactionListResponse response = transactionService.getAccountTransactions(accountId, null, 10);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getTransactions()).isEmpty();
        assertThat(response.getNextToken()).isNull();

        log.info("Correctly returned empty list for account with no transactions: {}", accountId);
    }

    @Test
    void testGetStampedTransactionsForCurrentYear() {
        // Given: Create test data - customer and account
        String customerId = createTestCustomer();
        String accountId = createTestAccount(customerId);
        
        // Create a transaction with pending sequence initially (as would happen via API)
        Transaction transaction1 = transactionService.createDeposit(
            accountId,
            "user123",
            "EUR",
            BigDecimal.valueOf(1000.50),
            Instant.now(),
            "DE89370400440532013000",
            "DE",
            "Invoice payment",
            "Business transaction"
        );
        
        // Simulate what the stream handler would do - manually update to stamped sequence for testing
        updateTransactionToStampedSequence(transaction1, "transaction-2025-000001");
        
        // Create another transaction 
        Transaction transaction2 = transactionService.createWithdrawal(
            accountId,
            "user456", 
            "USD",
            BigDecimal.valueOf(-500.75),
            Instant.now(),
            "GB82WEST12345698765432",
            "US",
            "Rent payment",
            "Monthly rent"
        );
        
        // Simulate stamping the second transaction
        updateTransactionToStampedSequence(transaction2, "transaction-2025-000002");
        
        // When: Get stamped transactions for current year
        List<Transaction> stampedTransactions = transactionService.getStampedTransactionsForCurrentYear(accountId);
        
        // Then: Should return only stamped transactions for current year
        assertThat(stampedTransactions).hasSize(2);
        assertThat(stampedTransactions.get(0).getSequence()).isEqualTo("transaction-2025-000001");
        assertThat(stampedTransactions.get(1).getSequence()).isEqualTo("transaction-2025-000002");
        
        // Verify they are sorted by sequence (chronological order)
        assertThat(stampedTransactions.get(0).getSequence()).isLessThan(stampedTransactions.get(1).getSequence());
        
        log.info("Successfully retrieved {} stamped transactions for current year", stampedTransactions.size());
    }
    
    private void updateTransactionToStampedSequence(Transaction transaction, String newSequence) {
        try {
            DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();

            DynamoDbTable<Transaction> table = enhancedClient.table(
                tableName, 
                TableSchema.fromBean(Transaction.class)
            );
            
            // Update the transaction with the new sequence
            transaction.setSequence(newSequence);
            transaction.setVersion(UUID.fromString(generateUUIDv7()));
            transaction.setUpdatedAt(Instant.now());
            
            table.putItem(transaction);
        } catch (Exception e) {
            log.error("Failed to update transaction sequence: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update transaction sequence", e);
        }
    }

    private String generateUUIDv7() {
        return Generators.timeBasedReorderedGenerator().generate().toString();
    }

    private String createTestCustomer() {
        return UUID.randomUUID().toString();
    }

    private String createTestAccount(String customerId) {
        return UUID.randomUUID().toString();
    }
} 
