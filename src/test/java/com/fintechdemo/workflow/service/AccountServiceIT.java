package com.fintechdemo.workflow.service;

import com.fintechdemo.workflow.BaseIntegrationTest;
import com.fintechdemo.workflow.model.Account;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import javax.inject.Inject;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Slf4j
class AccountServiceIT extends BaseIntegrationTest {

    @Inject
    private AccountService accountService;
    
    @Test
    void shouldCreateAccount() {
        // Given
        String customerId = UUID.randomUUID().toString();
        String accountName = "Test Checking Account";
        String currency = "USD";
        
        // When
        Account createdAccount = accountService.createAccount(customerId, accountName, currency);
        
        // Then
        assertThat(createdAccount).isNotNull();
        assertThat(createdAccount.getId()).isNotNull();
        assertThat(createdAccount.getCustomerId()).isEqualTo(customerId);
        assertThat(createdAccount.getName()).isEqualTo(accountName);
        assertThat(createdAccount.getCurrency()).isEqualTo(currency);
        assertThat(createdAccount.getType()).isEqualTo("ACCOUNT");
        assertThat(createdAccount.getEntityType()).isEqualTo("ACCOUNT");
        assertThat(createdAccount.getParent()).isEqualTo(customerId);
        assertThat(createdAccount.getAccountNumber()).startsWith("ACC");
        assertThat(createdAccount.getBalance()).isEqualTo(BigDecimal.ZERO);
        assertThat(createdAccount.getPending()).isEqualTo(BigDecimal.ZERO);
        assertThat(createdAccount.getStatus()).isEqualTo(Account.AccountStatus.ACTIVE);
        assertThat(createdAccount.getCreatedAt()).isNotNull();
        assertThat(createdAccount.getUpdatedAt()).isNotNull();
        assertThat(createdAccount.getCreatedAt()).isEqualTo(createdAccount.getUpdatedAt());
        
        // Verify version field is set with UUIDv7
        assertThat(createdAccount.getVersionUuid()).isNotNull();
        assertThat(createdAccount.getVersionString()).isNotNull();
        assertThatCode(() -> UUID.fromString(createdAccount.getVersionString())).doesNotThrowAnyException();
        
        // Verify UUID format
        assertThatCode(() -> UUID.fromString(createdAccount.getId())).doesNotThrowAnyException();
        
        log.info("Created account: {}", createdAccount);
    }

    @Test
    void shouldCreateAccountInDynamoDB() {
        // Given
        String customerId = UUID.randomUUID().toString();
        String accountName = "DynamoDB Test Account";
        String currency = "EUR";
        
        // When
        Account createdAccount = accountService.createAccount(customerId, accountName, currency);
        
        // Then - Verify account exists in DynamoDB
        List<Map<String, AttributeValue>> allItems = getAllItemsFromTable();
        assertThat(allItems).hasSize(1);
        
        Map<String, AttributeValue> accountItem = allItems.get(0);
        
        // Verify the expected attributes exist and match
        assertThat(accountItem.get("id")).isNotNull();
        assertThat(accountItem.get("id").s()).isEqualTo(createdAccount.getId());
        
        assertThat(accountItem.get("customerId")).isNotNull();
        assertThat(accountItem.get("customerId").s()).isEqualTo(customerId);
        
        assertThat(accountItem.get("name")).isNotNull();
        assertThat(accountItem.get("name").s()).isEqualTo(accountName);
        
        assertThat(accountItem.get("currency")).isNotNull();
        assertThat(accountItem.get("currency").s()).isEqualTo(currency);
        
        assertThat(accountItem.get("type")).isNotNull();
        assertThat(accountItem.get("type").s()).isEqualTo("ACCOUNT");
        
        assertThat(accountItem.get("parent")).isNotNull();
        assertThat(accountItem.get("parent").s()).isEqualTo(customerId);
        
        assertThat(accountItem.get("status")).isNotNull();
        assertThat(accountItem.get("status").s()).isEqualTo("ACTIVE");
        
        assertThat(accountItem.get("createdAt")).isNotNull();
        assertThat(accountItem.get("createdAt").s()).isNotEmpty();
        
        assertThat(accountItem.get("updatedAt")).isNotNull();
        assertThat(accountItem.get("updatedAt").s()).isNotEmpty();
        
        // Verify version field is stored
        assertThat(accountItem.get("version")).isNotNull();
        assertThat(accountItem.get("version").s()).isNotEmpty();
        assertThat(accountItem.get("version").s()).isEqualTo(createdAccount.getVersionString());
        
        log.info("Account stored in DynamoDB: {}", accountItem);
    }

    @Test
    void shouldFindAccountById() {
        // Given
        String customerId = UUID.randomUUID().toString();
        String accountName = "Findable Account";
        String currency = "GBP";
        Account createdAccount = accountService.createAccount(customerId, accountName, currency);
        
        // When
        Account foundAccount = accountService.findById(createdAccount.getId());
        
        // Then
        assertThat(foundAccount).isNotNull();
        assertThat(foundAccount.getId()).isEqualTo(createdAccount.getId());
        assertThat(foundAccount.getCustomerId()).isEqualTo(customerId);
        assertThat(foundAccount.getName()).isEqualTo(accountName);
        assertThat(foundAccount.getCurrency()).isEqualTo(currency);
        assertThat(foundAccount.getType()).isEqualTo("ACCOUNT");
        assertThat(foundAccount.getStatus()).isEqualTo(Account.AccountStatus.ACTIVE);
        assertThat(foundAccount.getCreatedAt()).isEqualTo(createdAccount.getCreatedAt());
        assertThat(foundAccount.getUpdatedAt()).isEqualTo(createdAccount.getUpdatedAt());
        
        log.info("Found account: {}", foundAccount);
    }

    @Test
    void shouldReturnNullForNonExistentAccount() {
        // Given
        String nonExistentId = UUID.randomUUID().toString();
        
        // When
        Account foundAccount = accountService.findById(nonExistentId);
        
        // Then
        assertThat(foundAccount).isNull();
        
        log.info("Correctly returned null for non-existent account ID: {}", nonExistentId);
    }

    @Test
    void shouldFindAccountsByCustomerId() {
        // Given
        String customerId = UUID.randomUUID().toString();
        
        // Create multiple accounts for the customer
        Account account1 = accountService.createAccount(customerId, "Checking Account", "USD");
        Account account2 = accountService.createAccount(customerId, "Savings Account", "USD");
        Account account3 = accountService.createAccount(customerId, "Euro Account", "EUR");
        
        // Create account for different customer (should not be returned)
        String otherCustomerId = UUID.randomUUID().toString();
        accountService.createAccount(otherCustomerId, "Other Account", "USD");
        
        // When
        List<Account> customerAccounts = accountService.getCustomerAccounts(customerId);
        
        // Then
        assertThat(customerAccounts).hasSize(3);
        
        // Verify all accounts belong to the correct customer
        for (Account account : customerAccounts) {
            assertThat(account.getCustomerId()).isEqualTo(customerId);
            assertThat(account.getParent()).isEqualTo(customerId);
            assertThat(account.getType()).isEqualTo("ACCOUNT");
            assertThat(account.getStatus()).isEqualTo(Account.AccountStatus.ACTIVE);
        }
        
        // Verify specific accounts are present
        assertThat(customerAccounts).extracting(Account::getName)
            .containsExactlyInAnyOrder("Checking Account", "Savings Account", "Euro Account");
        assertThat(customerAccounts).extracting(Account::getCurrency)
            .containsExactlyInAnyOrder("USD", "USD", "EUR");
            
        log.info("Found {} accounts for customer {}", customerAccounts.size(), customerId);
    }

    @Test
    void shouldReturnEmptyListForCustomerWithNoAccounts() {
        // Given
        String customerId = UUID.randomUUID().toString();
        
        // When
        List<Account> customerAccounts = accountService.getCustomerAccounts(customerId);
        
        // Then
        assertThat(customerAccounts).isEmpty();
        
        log.info("Correctly returned empty list for customer with no accounts: {}", customerId);
    }

    @Test
    void shouldHandleInvalidInputForAccountCreation() {
        // Test null customer ID
        assertThatThrownBy(() -> accountService.createAccount(null, "Test Account", "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer ID cannot be null or empty");
        
        // Test empty customer ID
        assertThatThrownBy(() -> accountService.createAccount("", "Test Account", "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer ID cannot be null or empty");
        
        // Test null account name
        assertThatThrownBy(() -> accountService.createAccount("customer123", null, "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account name cannot be null or empty");
        
        // Test empty account name
        assertThatThrownBy(() -> accountService.createAccount("customer123", "", "USD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Account name cannot be null or empty");
        
        // Test null currency
        assertThatThrownBy(() -> accountService.createAccount("customer123", "Test Account", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency cannot be null or empty");
        
        // Test empty currency
        assertThatThrownBy(() -> accountService.createAccount("customer123", "Test Account", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency cannot be null or empty");
        
        // Verify no accounts were created
        assertThat(getTableItemCount()).isZero();
    }

    @Test
    void shouldNormalizeCurrencyToUppercase() {
        // Given
        String customerId = UUID.randomUUID().toString();
        String accountName = "Currency Test Account";
        String currency = "usd"; // lowercase
        
        // When
        Account createdAccount = accountService.createAccount(customerId, accountName, currency);
        
        // Then
        assertThat(createdAccount.getCurrency()).isEqualTo("USD"); // Should be uppercase
        
        // Verify in database too
        Account foundAccount = accountService.findById(createdAccount.getId());
        assertThat(foundAccount.getCurrency()).isEqualTo("USD");
        
        log.info("Currency normalized from '{}' to '{}'", currency, createdAccount.getCurrency());
    }

    @Test
    void shouldTrimWhitespaceFromInputs() {
        // Given
        String customerId = UUID.randomUUID().toString();
        String accountName = "  Trimmed Account  ";
        String currency = "  EUR  ";
        
        // When
        Account createdAccount = accountService.createAccount(customerId, accountName, currency);
        
        // Then
        assertThat(createdAccount.getName()).isEqualTo("Trimmed Account");
        assertThat(createdAccount.getCurrency()).isEqualTo("EUR");
        
        log.info("Inputs trimmed successfully");
    }
} 
