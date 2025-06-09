package com.fintechdemo.workflow.service;

import com.fintechdemo.workflow.BaseIntegrationTest;
import com.fintechdemo.workflow.model.Customer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import javax.inject.Inject;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Slf4j
class CustomerServiceIT extends BaseIntegrationTest {

    @Inject
    private CustomerService customerService;

    @Test
    void shouldCreateCustomer() {
        // Given
        String customerName = "Test Customer Ltd";
        
        // When
        Customer createdCustomer = customerService.createCustomer(customerName);
        
        // Then
        assertThat(createdCustomer).isNotNull();
        assertThat(createdCustomer.getId()).isNotNull();
        assertThat(createdCustomer.getName()).isEqualTo(customerName);
        assertThat(createdCustomer.getType()).isEqualTo("CUSTOMER");
        assertThat(createdCustomer.getEntityType()).isEqualTo("CUSTOMER");
        assertThat(createdCustomer.getCreatedAt()).isNotNull();
        assertThat(createdCustomer.getUpdatedAt()).isNotNull();
        assertThat(createdCustomer.getCreatedAt()).isEqualTo(createdCustomer.getUpdatedAt());
        
        // Verify version field is set with UUIDv7
        assertThat(createdCustomer.getVersionUuid()).isNotNull();
        assertThat(createdCustomer.getVersionString()).isNotNull();
        assertThatCode(() -> UUID.fromString(createdCustomer.getVersionString())).doesNotThrowAnyException();
        
        // Verify UUID format
        assertThatCode(() -> UUID.fromString(createdCustomer.getId())).doesNotThrowAnyException();
        
        log.info("Created customer: {}", createdCustomer);
    }

    @Test
    void shouldCreateCustomerInDynamoDB() {
        // Given
        String customerName = "DynamoDB Test Customer";
        
        // When
        Customer createdCustomer = customerService.createCustomer(customerName);
        
        // Then - Verify customer exists in DynamoDB
        List<Map<String, AttributeValue>> allItems = getAllItemsFromTable();
        assertThat(allItems).hasSize(1);
        
        Map<String, AttributeValue> customerItem = allItems.get(0);
        
        // Verify the expected attributes exist and match
        assertThat(customerItem.get("id")).isNotNull();
        assertThat(customerItem.get("id").s()).isEqualTo(createdCustomer.getId());
        
        assertThat(customerItem.get("name")).isNotNull();
        assertThat(customerItem.get("name").s()).isEqualTo(customerName);
        
        assertThat(customerItem.get("type")).isNotNull();
        assertThat(customerItem.get("type").s()).isEqualTo("CUSTOMER");
        
        assertThat(customerItem.get("createdAt")).isNotNull();
        assertThat(customerItem.get("createdAt").s()).isNotEmpty(); // stored as ISO string
        
        assertThat(customerItem.get("updatedAt")).isNotNull();
        assertThat(customerItem.get("updatedAt").s()).isNotEmpty(); // stored as ISO string
        
        // Verify version field is stored
        assertThat(customerItem.get("version")).isNotNull();
        assertThat(customerItem.get("version").s()).isNotEmpty();
        assertThat(customerItem.get("version").s()).isEqualTo(createdCustomer.getVersionString());
        
        log.info("Customer stored in DynamoDB: {}", customerItem);
    }

    @Test
    void shouldFindCustomerById() {
        // Given
        String customerName = "Findable Customer Corp";
        Customer createdCustomer = customerService.createCustomer(customerName);
        
        // When
        Customer foundCustomer = customerService.findById(createdCustomer.getId());
        
        // Then
        assertThat(foundCustomer).isNotNull();
        assertThat(foundCustomer.getId()).isEqualTo(createdCustomer.getId());
        assertThat(foundCustomer.getName()).isEqualTo(customerName);
        assertThat(foundCustomer.getType()).isEqualTo("CUSTOMER");
        assertThat(foundCustomer.getEntityType()).isEqualTo("CUSTOMER");
        assertThat(foundCustomer.getCreatedAt()).isEqualTo(createdCustomer.getCreatedAt());
        assertThat(foundCustomer.getUpdatedAt()).isEqualTo(createdCustomer.getUpdatedAt());
        
        log.info("Found customer: {}", foundCustomer);
    }

    @Test
    void shouldReturnNullForNonExistentCustomer() {
        // Given
        String nonExistentId = UUID.randomUUID().toString();
        
        // When
        Customer foundCustomer = customerService.findById(nonExistentId);
        
        // Then
        assertThat(foundCustomer).isNull();
        
        log.info("Correctly returned null for non-existent customer ID: {}", nonExistentId);
    }

    @Test
    void shouldCreateMultipleCustomers() {
        // Given
        String[] customerNames = {
            "First Customer Inc",
            "Second Customer Ltd",
            "Third Customer Corp"
        };
        
        // When
        Customer[] customers = new Customer[customerNames.length];
        for (int i = 0; i < customerNames.length; i++) {
            customers[i] = customerService.createCustomer(customerNames[i]);
        }
        
        // Then
        assertThat(getTableItemCount()).isEqualTo(3);
        
        for (int i = 0; i < customers.length; i++) {
            assertThat(customers[i]).isNotNull();
            assertThat(customers[i].getName()).isEqualTo(customerNames[i]);
            assertThat(customers[i].getId()).isNotNull();
            
            // Verify each customer can be retrieved
            Customer retrieved = customerService.findById(customers[i].getId());
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.getName()).isEqualTo(customerNames[i]);
        }
        
        log.info("Successfully created and verified {} customers", customers.length);
    }

    @Test
    void shouldHandleEmptyCustomerName() {
        // Given
        String emptyName = "";
        
        // When & Then
        assertThatThrownBy(() -> customerService.createCustomer(emptyName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer name cannot be null or empty");
        
        // Verify no customer was created
        assertThat(getTableItemCount()).isZero();
    }

    @Test
    void shouldHandleNullCustomerName() {
        // When & Then
        assertThatThrownBy(() -> customerService.createCustomer(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Customer name cannot be null or empty");
        
        // Verify no customer was created
        assertThat(getTableItemCount()).isZero();
    }
} 
