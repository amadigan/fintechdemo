package com.fintechdemo.workflow;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton LocalStack container shared across all integration tests.
 * This ensures we only start one container per JVM, avoiding networking issues
 * and reducing test execution time.
 */
@Slf4j
public class SharedLocalStackContainer {
    
    private static LocalStackContainer instance;
    private static final Object lock = new Object();
    
    private SharedLocalStackContainer() {
        // Private constructor to prevent instantiation
    }
    
    public static LocalStackContainer getInstance() {
        if (instance == null) {
            synchronized (lock) {
                if (instance == null) {
                    log.info("Creating shared LocalStack container");
                    instance = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                            .withServices(LocalStackContainer.Service.DYNAMODB);
                    
                    // Start the container
                    instance.start();
                    log.info("Shared LocalStack container started successfully");
                    
                    // Register shutdown hook to stop container when JVM exits
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        log.info("Stopping shared LocalStack container");
                        if (instance != null) {
                            instance.stop();
                        }
                    }));
                }
            }
        }
        return instance;
    }
    
    /**
     * Check if the container is started and healthy
     */
    public static boolean isStarted() {
        return instance != null && instance.isRunning();
    }
} 
