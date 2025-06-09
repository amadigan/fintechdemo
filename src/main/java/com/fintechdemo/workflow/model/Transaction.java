package com.fintechdemo.workflow.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@DynamoDbBean
public class Transaction extends BaseEntity {
    public static final String ENTITY_TYPE = "TRANSACTION";
    
    private String accountId;        // The account this transaction belongs to
    private String userId;           // User who initiated the transaction
    private String currency;         // Transaction currency (EUR, USD, etc.)
    private BigDecimal amount;       // Transaction amount
    private Instant transactedAt;    // When the transaction was made
    private String beneficiaryIBAN;  // For withdrawals/payments
    private String payorIBAN;        // For deposits
    private String originatingCountry; // Country code
    private String paymentRef;       // Payment reference
    private String purposeRef;       // Purpose reference
    private TransactionType transactionType; // DEPOSIT or WITHDRAWAL
    
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
    
    public enum TransactionType {
        DEPOSIT,
        WITHDRAWAL
    }
} 
