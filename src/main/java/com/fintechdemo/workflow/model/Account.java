package com.fintechdemo.workflow.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@DynamoDbBean
public class Account extends BaseEntity {
    public static final String ENTITY_TYPE = "ACCOUNT";
    
    private String customerId;
    private String name; // Account name like "checking", "savings", etc.
    private String accountNumber;
    private String currency;
    private BigDecimal balance;
    private BigDecimal pending;
    private AccountStatus status;
    private String latestTransaction; // Latest transaction sequence (initially null/not present)

    @Override
    public String getEntityType() {
        return ENTITY_TYPE;
    }

    // Override parent getter to add GSI annotation
    @Override
    @DynamoDbSecondaryPartitionKey(indexNames = "parent-sequence-index")
    public String getParent() {
        return super.getParent();
    }

    // Override sequence getter to add GSI annotation  
    @Override
    @DynamoDbSecondarySortKey(indexNames = "parent-sequence-index")
    public String getSequence() {
        return super.getSequence();
    }

    public enum AccountStatus {
        PENDING,
        ACTIVE,
        FROZEN,
        CLOSED
    }
} 
