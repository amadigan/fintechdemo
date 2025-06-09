package com.fintechdemo.workflow.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@DynamoDbBean
public class Customer extends BaseEntity {
    public static final String ENTITY_TYPE = "CUSTOMER";
    
    private String name;

    @Override
    public String getEntityType() {
        return ENTITY_TYPE;
    }
} 
