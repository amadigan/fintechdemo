package com.fintechdemo.workflow.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.time.Instant;
import java.util.UUID;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@DynamoDbBean
public abstract class BaseEntity {
    private UUID id;
    private String type;
    private String parent;
    private String sequence;
    private UUID version; // UUIDv7 for optimistic concurrency control and audit trail
    private Instant createdAt;
    private Instant updatedAt;

    @DynamoDbPartitionKey
    public String getId() {
        return id != null ? id.toString() : null;
    }

    public void setId(String idString) {
        this.id = idString != null ? UUID.fromString(idString) : null;
    }

    public UUID getUuid() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    // Version field for DynamoDB
    public String getVersionString() {
        return version != null ? version.toString() : null;
    }

    public void setVersionString(String versionString) {
        this.version = versionString != null ? UUID.fromString(versionString) : null;
    }

    public UUID getVersionUuid() {
        return version;
    }

    public void setVersion(UUID version) {
        this.version = version;
    }

    public abstract String getEntityType();
} 
