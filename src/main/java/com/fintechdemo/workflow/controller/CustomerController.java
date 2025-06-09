package com.fintechdemo.workflow.controller;

import com.fintechdemo.workflow.model.Customer;
import com.fintechdemo.workflow.service.CustomerService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @PostMapping
    public ResponseEntity<Customer> createCustomer(@RequestBody CreateCustomerRequest request) {
        log.info("Creating new customer with name: {}", request.getName());
        Customer createdCustomer = customerService.createCustomer(request.getName());
        return ResponseEntity.status(201).body(createdCustomer);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Customer> getCustomer(@PathVariable UUID id) {
        log.info("Getting customer with id: {}", id);
        Customer customer = customerService.getCustomer(id.toString());
        return ResponseEntity.ok(customer);
    }

    @Data
    public static class CreateCustomerRequest {
        private String name;
    }
} 
