package com.fintechdemo.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:eu-west-1}")
    private String region;

    @Value("${app.dynamodb.endpoint:#{null}}")
    private String endpoint;

    @Value("${aws.accessKeyId:#{null}}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey:#{null}}")
    private String secretAccessKey;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var clientBuilder = DynamoDbClient.builder()
                .region(Region.of(region));

        // Configure for LocalStack/testing if endpoint is provided
        if (endpoint != null && !endpoint.isEmpty()) {
            clientBuilder.endpointOverride(URI.create(endpoint));
            
            if (accessKeyId != null && secretAccessKey != null) {
                clientBuilder.credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                        )
                );
            }
        }

        return clientBuilder.build();
    }
} 
