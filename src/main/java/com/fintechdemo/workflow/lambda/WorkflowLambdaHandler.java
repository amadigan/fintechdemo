package com.fintechdemo.workflow.lambda;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fintechdemo.workflow.controller.CustomerController;
import com.fintechdemo.workflow.model.Customer;
import com.fintechdemo.workflow.service.CustomerService;
import com.fintechdemo.workflow.service.AccountService;
import com.fintechdemo.workflow.service.TransactionService;
import com.fintechdemo.workflow.controller.TransactionController;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.function.Function;

@SpringBootApplication(scanBasePackages = "com.fintechdemo.workflow")
public class WorkflowLambdaHandler {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> handleRequest(
            CustomerService customerService,
            AccountService accountService,
            TransactionService transactionService,
            TransactionController transactionController,
            ObjectMapper objectMapper) {
        return request -> {
            try {
                String path = request.getPath();
                String method = request.getHttpMethod();
                
                // Route to appropriate controller based on path
                // Check for customer accounts first (more specific path)
                if (path != null && path.matches("/api/customers/[^/]+/accounts")) {
                    return handleAccountRequest(request, accountService, transactionService, transactionController, objectMapper);
                } else if (path != null && path.startsWith("/api/customers")) {
                    return handleCustomerRequest(request, customerService, objectMapper);
                } else if (path != null && path.startsWith("/api/accounts")) {
                    return handleAccountRequest(request, accountService, transactionService, transactionController, objectMapper);
                } else if (path != null && path.equals("/health")) {
                    // Simple health check endpoint
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(200)
                        .withBody("{\"status\":\"OK\",\"message\":\"Lambda function is running with SnapStart\"}");
                } else {
                    return new APIGatewayProxyResponseEvent()
                        .withStatusCode(404)
                        .withBody("{\"error\":\"Not Found\"}");
                }
            } catch (Exception e) {
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
            }
        };
    }

    private APIGatewayProxyResponseEvent handleCustomerRequest(APIGatewayProxyRequestEvent request, 
                                                               CustomerService customerService, 
                                                               ObjectMapper objectMapper) {
        String method = request.getHttpMethod();
        String path = request.getPath();
        
        try {
            if ("GET".equals(method) && path.matches("/api/customers/[^/]+")) {
                // Extract ID from path
                String id = path.substring("/api/customers/".length());
                Customer customer = customerService.getCustomer(id);
                String responseBody = objectMapper.writeValueAsString(customer);
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(responseBody);
            } else if ("POST".equals(method) && "/api/customers".equals(path)) {
                CustomerController.CreateCustomerRequest customerRequest = 
                    objectMapper.readValue(request.getBody(), CustomerController.CreateCustomerRequest.class);
                
                Customer customer = customerService.createCustomer(customerRequest.getName());
                String responseBody = objectMapper.writeValueAsString(customer);
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(201)
                    .withBody(responseBody);
            }
            
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(404)
                .withBody("{\"error\":\"Not Found\"}");
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent handleAccountRequest(APIGatewayProxyRequestEvent request,
                                                              AccountService accountService,
                                                              TransactionService transactionService,
                                                              TransactionController transactionController,
                                                              ObjectMapper objectMapper) {
        String method = request.getHttpMethod();
        String path = request.getPath();
        
        try {
            // Handle customer accounts endpoint  
            if ("GET".equals(method) && path.matches("/api/customers/[^/]+/accounts")) {
                // Extract customer ID from path: /api/customers/{customerId}/accounts
                String customerId = path.substring("/api/customers/".length());
                customerId = customerId.substring(0, customerId.indexOf("/accounts"));
                
                java.util.List<com.fintechdemo.workflow.model.Account> accounts = accountService.getCustomerAccounts(customerId);
                String responseBody = objectMapper.writeValueAsString(accounts);
                
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(responseBody);
            }
            // Handle transaction-related endpoints
            else if ("POST".equals(method) && path.matches("/api/accounts/[^/]+/deposit")) {
                return handleDepositRequest(request, transactionService, objectMapper);
            } else if ("POST".equals(method) && path.matches("/api/accounts/[^/]+/transaction")) {
                return handleWithdrawalRequest(request, transactionService, objectMapper);
            } else if ("GET".equals(method) && path.matches("/api/accounts/[^/]+/transactions\\.csv")) {
                return handleGetTransactionsCsvRequest(request, transactionController);
            } else if ("GET".equals(method) && path.matches("/api/accounts/[^/]+/transactions")) {
                return handleGetTransactionsRequest(request, transactionService, objectMapper);
            } else if ("GET".equals(method) && path.matches("/api/accounts/[^/]+")) {
                String id = path.substring("/api/accounts/".length());
                com.fintechdemo.workflow.model.Account account = accountService.getAccount(id);
                String responseBody = objectMapper.writeValueAsString(account);
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(200)
                    .withBody(responseBody);
            } else if ("POST".equals(method) && "/api/accounts".equals(path)) {
                com.fintechdemo.workflow.controller.CreateAccountRequest accountRequest = 
                    objectMapper.readValue(request.getBody(), com.fintechdemo.workflow.controller.CreateAccountRequest.class);
                
                com.fintechdemo.workflow.model.Account account = accountService.createAccount(
                    accountRequest.getCustomerId(), 
                    accountRequest.getName(), 
                    accountRequest.getCurrency()
                );
                String responseBody = objectMapper.writeValueAsString(account);
                return new APIGatewayProxyResponseEvent()
                    .withStatusCode(201)
                    .withBody(responseBody);
            }
            
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(404)
                .withBody("{\"error\":\"Not Found\"}");
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent handleDepositRequest(APIGatewayProxyRequestEvent request,
                                                              TransactionService transactionService,
                                                              ObjectMapper objectMapper) {
        try {
            String path = request.getPath();
            String accountId = path.substring("/api/accounts/".length(), path.indexOf("/deposit"));
            
            com.fintechdemo.workflow.controller.CreateDepositRequest depositRequest = 
                objectMapper.readValue(request.getBody(), com.fintechdemo.workflow.controller.CreateDepositRequest.class);
            
            com.fintechdemo.workflow.model.Transaction transaction = transactionService.createDeposit(
                accountId,
                depositRequest.getUserId(),
                depositRequest.getCurrency(),
                depositRequest.getAmount(),
                depositRequest.getTransactedAt(),
                depositRequest.getPayorIBAN(),
                depositRequest.getOriginatingCountry(),
                depositRequest.getPaymentRef(),
                depositRequest.getPurposeRef()
            );
            
            String responseBody = objectMapper.writeValueAsString(transaction);
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(201)
                .withBody(responseBody);
        } catch (IllegalArgumentException e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent handleWithdrawalRequest(APIGatewayProxyRequestEvent request,
                                                                 TransactionService transactionService,
                                                                 ObjectMapper objectMapper) {
        try {
            String path = request.getPath();
            String accountId = path.substring("/api/accounts/".length(), path.indexOf("/transaction"));
            
            com.fintechdemo.workflow.controller.CreateWithdrawalRequest withdrawalRequest = 
                objectMapper.readValue(request.getBody(), com.fintechdemo.workflow.controller.CreateWithdrawalRequest.class);
            
            com.fintechdemo.workflow.model.Transaction transaction = transactionService.createWithdrawal(
                accountId,
                withdrawalRequest.getUserId(),
                withdrawalRequest.getCurrency(),
                withdrawalRequest.getAmount(),
                withdrawalRequest.getTransactedAt(),
                withdrawalRequest.getBeneficiaryIBAN(),
                withdrawalRequest.getOriginatingCountry(),
                withdrawalRequest.getPaymentRef(),
                withdrawalRequest.getPurposeRef()
            );
            
            String responseBody = objectMapper.writeValueAsString(transaction);
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(201)
                .withBody(responseBody);
        } catch (IllegalArgumentException e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(400)
                .withBody("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent handleGetTransactionsRequest(APIGatewayProxyRequestEvent request,
                                                                      TransactionService transactionService,
                                                                      ObjectMapper objectMapper) {
        try {
            String path = request.getPath();
            String accountId = path.substring("/api/accounts/".length(), path.indexOf("/transactions"));
            
            // Extract query parameters
            String nextToken = null;
            Integer limit = 20;
            if (request.getQueryStringParameters() != null) {
                nextToken = request.getQueryStringParameters().get("nextToken");
                String limitStr = request.getQueryStringParameters().get("limit");
                if (limitStr != null) {
                    limit = Integer.parseInt(limitStr);
                }
            }
            
            com.fintechdemo.workflow.controller.TransactionListResponse response = 
                transactionService.getAccountTransactions(accountId, nextToken, limit);
            
            String responseBody = objectMapper.writeValueAsString(response);
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(200)
                .withBody(responseBody);
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    private APIGatewayProxyResponseEvent handleGetTransactionsCsvRequest(APIGatewayProxyRequestEvent request,
                                                                         TransactionController transactionController) {
        try {
            String path = request.getPath();
            String accountId = path.substring("/api/accounts/".length(), path.indexOf("/transactions.csv"));
            
            org.springframework.http.ResponseEntity<String> response = transactionController.getTransactionsCsv(accountId);
            
            APIGatewayProxyResponseEvent apiResponse = new APIGatewayProxyResponseEvent()
                .withStatusCode(response.getStatusCode().value())
                .withBody(response.getBody());
            
            // Set CSV headers
            if (response.getHeaders().getContentType() != null) {
                apiResponse.withHeaders(java.util.Map.of(
                    "Content-Type", response.getHeaders().getContentType().toString(),
                    "Content-Disposition", response.getHeaders().getFirst("Content-Disposition")
                ));
            }
            
            return apiResponse;
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                .withStatusCode(500)
                .withBody("{\"error\":\"Internal Server Error: " + e.getMessage() + "\"}");
        }
    }

    public static void main(String[] args) {
        SpringApplication.run(WorkflowLambdaHandler.class, args);
    }
} 
