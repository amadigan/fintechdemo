# Implementation Plan

This will be a single module maven project that builds an AWS Lambda function zip for the Java 21 runtime. The lambda will be SnapStart enabled. In a larger 
system, a multi-module maven project would be used, and functionality would be split into separate lambda functions. The way functionality is split might take 
many forms, but at minimum "frontend" (REST) and "backend" (event processing) would be separate lambda functions.

This will be a Spring Boot application running on AWS Lambda with a DynamoDB database. Transactions written to by REST will lack a sequence number. DynamoDB
will stream the transactions to Lambda, and the Lambda will transactionally write the sequence number to the transaction. Deposits increase the balance of 
the account, and payments decrease the "pending" amount. It is assumed a later process would "settle" pending transactions to the balance. In a larger system,
deposits may also be "pending" before settling.

## REST API

For this simple example, all functionality will be exposed via the REST API. Ordinarily, some data would also be expected to come in via webhooks, or possibly 
files delivered via SFTP.

### API Endpoints

#### POST /customer

Create a new customer.

Request:
```json
{
    "name": "Leviathan SA"
}
```

Response:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "name": "Leviathan SA",
		"created": "08-JUN-25 13:00:00"
}
```


#### POST /account

Request:
```json
{
    "customerId": "123e4567-e89b-12d3-a456-426614174000",
		"ref": "Euro Current Account",
    "currency": "EUR"
}
```

Response:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "customerId": "123e4567-e89b-12d3-a456-426614174000",
    "ref": "Euro Current Account",
    "currency": "EUR",
		"created": "08-JUN-25 13:00:00",
		"balance": 0,
		"pending": 0
}
```

#### POST /account/{accountId}/payment

Request:
```json
{
    "userId": "string",
		"currency": "EUR",
		"amount": 1000,
		"transactedAt" : "24-JAN-18 10:27:44", 
		"beneficiaryIBAN": "string",
		"originatingCountry": "FR",
		"paymentRef": "Invoice nr 12345",
		"purposeRef": "invoice payment"
}
```

Response:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "accountId": "123e4567-e89b-12d3-a456-426614174000",
    "userId": "string",
    "currency": "EUR",
    "amount": 1000,
		"transactedAt" : "24-JAN-18 10:27:44", 
		"beneficiaryIBAN": "string",
		"originatingCountry": "FR",
		"paymentRef": "Invoice nr 12345",
		"purposeRef": "invoice payment",
		"created": "08-JUN-25 13:00:00"
}
```

#### POST /account/{accountId}/deposit
(this would normally probably a be a webhook rather than a REST endpoint)

Request:
```json
{
    "userId": "string",
		"currency": "EUR",
		"amount": 1000,
		"transactedAt" : "24-JAN-18 10:27:44", 
		"payorIBAN": "string",
		"originatingCountry": "FR",
		"paymentRef": "Invoice nr 12345",
		"purposeRef": "invoice payment"
}
```

Response:
```json
{
    "id": "123e4567-e89b-12d3-a456-426614174000",
    "accountId": "123e4567-e89b-12d3-a456-426614174000",
    "userId": "string",
    "currency": "EUR",
    "amount": 1000,
		"transactedAt" : "24-JAN-18 10:27:44", 
		"payorIBAN": "string",
		"originatingCountry": "FR",
		"paymentRef": "Invoice nr 12345",
		"purposeRef": "invoice payment",
		"created": "08-JUN-25 13:00:00"
}
```

#### GET /account/{accountId}/transaction

Response:
```json
{
	"transactions": [
		{
		"id": "123e4567-e89b-12d3-a456-426614174000",
		"sequence": "2025-06-08-000001",
		"type": "payment",
		"amount": 1000,
		"currency": "EUR",
		"transactedAt" : "24-JAN-18 10:27:44", 
		"beneficiaryIBAN": "string",
		"originatingCountry": "FR",
		"paymentRef": "Invoice nr 12345",
		"purposeRef": "invoice payment",
		"created": "08-JUN-25 13:00:00"
	}],
	"nextToken": "2025-06-08-000002"
}

#### GET /account/{accountId}/transaction.csv

Response:
```csv
"sequence","created","type","amount","currency","transactedAt","beneficiaryIBAN","originatingCountry","paymentRef","purposeRef"
"2025-06-08-000001","08-JUN-25 13:00:00","payment","1000","EUR","24-JAN-18 10:27:44","string","FR","Invoice nr 12345","invoice payment"
```

## Data Model

All data will be stored in a single DynamoDB table. The data model is designed to allow for a multi-region deployment
with many reader/writer regions and a single `processing` region (with failover). In general, only the processing
region would have lambda triggers for DynamoDB streams.

The primary key of the table is `id` and is a UUID, represented as a string for this prototype. A secondary index of
children has a `parentId` and a `sequence`, both strings. The `sequence` may be prefixed with a type to allow different
types of children to be stored in the same index. All objects have `created` and `updated` timestamps, as well as a `version`,
which is a UUIDv7 unless otherwise specified.

For an account, the parentId points to the customer, the sequence is "account-{UUIDv7}". Account creation should be
low volume, so a UUIDv7 is considered safe.

Transactions are stored in the table with a `parentId` of the account, and a `sequence` of "transaction-YYYYMMDD-000001". 
The account object stores the `latestTransactionId`, which is the sequence of the last transaction. The message processor
(the DynamoDB stream trigger) will transactionally stamp new transactions with the next sequence number and update the
account object. This process uses an optimistic locking mechanism, and may retry the transaction if multiple operations
attempt to update the account object at the same time.

## Testing

A basic smoke test is included as `test-comprehensive.sh`. This is for basic validation after deployment. I tend to be distrustful of tests that mock complex
dependencies, such as databases. Therefore, most of my tests take the form of "integration tests" using surefire. DynamoDB is run in a docker container
for the integration tests.
