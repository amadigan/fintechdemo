package com.fintechdemo.workflow.service;

import com.fintechdemo.workflow.model.Customer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedReorderedGenerator;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Lazy  // Lazy initialization to prevent issues with SnapStart
public class CustomerService {
    
    private final DynamoDbClient dynamoDbClient;
    
    @Value("${app.dynamodb.table-name:fintechdemo-workflow-dev}")
    private String tableName;
    
    // UUIDv7 generator for version fields
    private static final TimeBasedReorderedGenerator UUID_V7_GENERATOR = Generators.timeBasedReorderedGenerator();
    
    public Customer createCustomer(String name) {
        log.info("Creating customer with name: {}", name);
        
        // Validate input
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Customer name cannot be null or empty");
        }
        
        // Generate UUIDv4 for new customer
        UUID customerId = UUID.randomUUID();
        // Generate UUIDv7 for version (optimistic concurrency control)
        UUID versionId = UUID_V7_GENERATOR.generate();
        Instant now = Instant.now();
        
        Customer customer = Customer.builder()
            .id(customerId)
            .type(Customer.ENTITY_TYPE)
            .name(name)
            .version(versionId)
            .createdAt(now)
            .updatedAt(now)
            .build();
        
        // Save to DynamoDB
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Customer> table = enhancedClient.table(tableName, TableSchema.fromBean(Customer.class));
        
        try {
            table.putItem(customer);
            log.info("Successfully created customer with ID: {}", customerId);
            return customer;
        } catch (Exception e) {
            log.error("Failed to create customer: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create customer", e);
        }
    }

    public Customer getCustomer(String id) {
        log.info("Retrieving customer with ID: {}", id);
        
        // Retrieve from DynamoDB
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Customer> table = enhancedClient.table(tableName, TableSchema.fromBean(Customer.class));
        
        try {
            Key key = Key.builder()
                .partitionValue(id)
                .build();
                
            Customer customer = table.getItem(key);
            
            if (customer == null) {
                log.warn("Customer not found with ID: {}", id);
                throw new RuntimeException("Customer not found with ID: " + id);
            }
            
            log.info("Successfully retrieved customer: {}", customer.getName());
            return customer;
        } catch (Exception e) {
            log.error("Failed to retrieve customer with ID {}: {}", id, e.getMessage(), e);
            throw new RuntimeException("Failed to retrieve customer", e);
        }
    }

    public Customer findById(String id) {
        log.info("Finding customer with ID: {}", id);
        
        if (id == null || id.trim().isEmpty()) {
            log.warn("Invalid customer ID provided: {}", id);
            return null;
        }
        
        // Retrieve from DynamoDB
        DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder()
            .dynamoDbClient(dynamoDbClient)
            .build();
            
        DynamoDbTable<Customer> table = enhancedClient.table(tableName, TableSchema.fromBean(Customer.class));
        
        try {
            Key key = Key.builder()
                .partitionValue(id)
                .build();
                
            Customer customer = table.getItem(key);
            
            if (customer == null) {
                log.info("Customer not found with ID: {}", id);
                return null;
            }
            
            log.info("Successfully found customer: {}", customer.getName());
            return customer;
        } catch (Exception e) {
            log.error("Failed to find customer with ID {}: {}", id, e.getMessage(), e);
            return null;
        }
    }
} 
