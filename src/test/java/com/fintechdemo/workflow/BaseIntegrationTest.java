package com.fintechdemo.workflow;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import javax.inject.Inject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.reflect.Field;
import org.testcontainers.containers.localstack.LocalStackContainer;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.List;

/**
 * Base class for integration tests that provides a shared LocalStack container
 * and DynamoDB table management.
 * 
 * Uses a singleton LocalStack container shared across all test classes
 * to avoid the overhead and conflicts of starting multiple containers.
 * Each test class gets its own uniquely named table to prevent data conflicts.
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    // Use singleton LocalStack container
    private static final LocalStackContainer localStack = SharedLocalStackContainer.getInstance();

    @Value("${app.dynamodb.table-name}")
    private String baseTableName;
    
    protected String tableName;

    @Inject
    protected DynamoDbClient dynamoDbClient;
    
    @Inject
    private ApplicationContext applicationContext;
    
    // Generate unique table name per test class to avoid conflicts with shared container
    private void initializeTableName() {
        if (tableName == null) {
            String className = this.getClass().getSimpleName();
            // Use thread ID to ensure uniqueness even with parallel execution
            long threadId = Thread.currentThread().getId();
            tableName = baseTableName + "-" + className + "-" + threadId;
        }
    }
    
    // Update services with the dynamically generated table name
    private void updateServicesWithTableName() {
        try {
            // Update CustomerService if it exists
            if (applicationContext.containsBean("customerService")) {
                Object customerService = applicationContext.getBean("customerService");
                setTableNameViaReflection(customerService, tableName);
            }
            
            // Update AccountService if it exists  
            if (applicationContext.containsBean("accountService")) {
                Object accountService = applicationContext.getBean("accountService");
                setTableNameViaReflection(accountService, tableName);
            }
            
            // Update TransactionService if it exists
            if (applicationContext.containsBean("transactionService")) {
                Object transactionService = applicationContext.getBean("transactionService");
                setTableNameViaReflection(transactionService, tableName);
            }
        } catch (Exception e) {
            log.warn("Failed to update service table names: {}", e.getMessage());
        }
    }
    
    private void setTableNameViaReflection(Object service, String newTableName) {
        try {
            Field tableNameField = service.getClass().getDeclaredField("tableName");
            tableNameField.setAccessible(true);
            tableNameField.set(service, newTableName);
            log.debug("Updated table name to {} for service {}", newTableName, service.getClass().getSimpleName());
        } catch (Exception e) {
            log.warn("Could not update tableName field for {}: {}", service.getClass().getSimpleName(), e.getMessage());
        }
    }

    @DynamicPropertySource
    static void setProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.accessKeyId", () -> localStack.getAccessKey());
        registry.add("aws.secretAccessKey", () -> localStack.getSecretKey());
        registry.add("aws.region", () -> localStack.getRegion());
        registry.add("app.dynamodb.endpoint", () -> localStack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString());
    }

    @BeforeEach
    protected void setUpTable() {
        initializeTableName();
        log.info("Setting up DynamoDB table: {}", tableName);
        createTable();
        updateServicesWithTableName();
        log.info("Table {} created successfully", tableName);
    }

    @AfterEach
    protected void tearDownTable() {
        log.info("Tearing down DynamoDB table: {}", tableName);
        try {
            deleteTable();
            log.info("Table {} deleted successfully", tableName);
        } catch (Exception e) {
            log.warn("Failed to delete table {}: {}", tableName, e.getMessage());
        }
    }

    private void createTable() {
        try {
            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .keySchema(
                            KeySchemaElement.builder()
                                    .attributeName("id")
                                    .keyType(KeyType.HASH)
                                    .build()
                    )
                    .attributeDefinitions(
                            AttributeDefinition.builder()
                                    .attributeName("id")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("parent")
                                    .attributeType(ScalarAttributeType.S)
                                    .build(),
                            AttributeDefinition.builder()
                                    .attributeName("sequence")
                                    .attributeType(ScalarAttributeType.S)
                                    .build()
                    )
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("parent-sequence-index")
                                    .keySchema(
                                            KeySchemaElement.builder()
                                                    .attributeName("parent")
                                                    .keyType(KeyType.HASH)
                                                    .build(),
                                            KeySchemaElement.builder()
                                                    .attributeName("sequence")
                                                    .keyType(KeyType.RANGE)
                                                    .build()
                                    )
                                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                                    .provisionedThroughput(ProvisionedThroughput.builder()
                                            .readCapacityUnits(5L)
                                            .writeCapacityUnits(5L)
                                            .build())
                                    .build()
                    )
                    .provisionedThroughput(ProvisionedThroughput.builder()
                            .readCapacityUnits(5L)
                            .writeCapacityUnits(5L)
                            .build())
                    .build();

            dynamoDbClient.createTable(request);

            // Wait for table to become active
            waitForTableToBeActive();

        } catch (ResourceInUseException e) {
            log.info("Table {} already exists, skipping creation", tableName);
        }
    }

    private void waitForTableToBeActive() {
        boolean tableActive = false;
        int attempts = 0;
        int maxAttempts = 30;

        while (!tableActive && attempts < maxAttempts) {
            try {
                DescribeTableResponse response = dynamoDbClient.describeTable(
                        DescribeTableRequest.builder().tableName(tableName).build()
                );

                TableStatus status = response.table().tableStatus();
                log.debug("Table {} status: {}", tableName, status);

                if (status == TableStatus.ACTIVE) {
                    tableActive = true;
                } else {
                    Thread.sleep(1000);
                    attempts++;
                }
            } catch (Exception e) {
                log.warn("Error checking table status: {}", e.getMessage());
                attempts++;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for table", ie);
                }
            }
        }

        if (!tableActive) {
            throw new RuntimeException("Table " + tableName + " did not become active within timeout");
        }
    }

    private void deleteTable() {
        try {
            dynamoDbClient.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());
        } catch (ResourceNotFoundException e) {
            log.debug("Table {} does not exist, skipping deletion", tableName);
        }
    }

    /**
     * Helper method to get all items from the table (for testing purposes)
     */
    protected List<java.util.Map<String, AttributeValue>> getAllItemsFromTable() {
        ScanRequest scanRequest = ScanRequest.builder()
                .tableName(tableName)
                .build();

        ScanResponse response = dynamoDbClient.scan(scanRequest);
        return response.items();
    }

    /**
     * Helper method to count items in the table
     */
    protected int getTableItemCount() {
        return getAllItemsFromTable().size();
    }
} 
