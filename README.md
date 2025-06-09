# Fintech Demo

This is a functional prototype of a multi-region account ledger in Java on AWS Lambda, using DynamoDB as the database. In a full deployment, a DynamoDB global
table would be used, and transactional processing would occur in a "main" region driven by DynamoDB Streams.

## Data Model
The data model has only three types:

- Customer
- Account - many per Customer
- Transaction - many per Account

All three types are stored in a single DynamoDB table, and all have several core fields:
- id - UUID
- type - string
- created - timestamp
- updated - timestamp
- version - UUID
- parent (optional) - UUID of the parent object
- sequence - string, type dependent value, represents the ID of the relationship to the parent object

Customers do not have parent or sequence fields.

## Transaction Processing

This simple prototype does not perform actual financial transactions. However, it does commit each transaction received and assign it a sequence value. This is
performed using an optimistic locking mechanism, and also updates the account balance. The sequence value can be used to make idempotent calls to external
systems, or the assignment of the sequence value can be used to generate events (which would trigger a downstream financial process). Transaction processing
is triggered by a DynamoDB Streams trigger. In a production deployment, this trigger would only be in the "main" region, ensuring transactional consistency in
DynamoDB. Additionally, a dead letter queue would be used to handle failed transactions.

## Scalability

This design uses Lambda to process incoming requests, and is designed to be deployed to multiple regions, with Geo-IP based routing via Route 53. Both Lambda
and DynamoDB come with very high scalability. The multi-region design reduces latency for users, provides fault tolerance (an unhealth region can be taken offline),
and spreads request load across multiple regions. This example does not use Lambda SnapStart, but that would also be used to reduce cold start times after
deployment.

## Authentication and Authorization

I have skipped authentication and authorization for this prototype. An authorization lambda would likely be used, but beyond that further requirements are needed.

## Deployment

This project can be deployed to an AWS account. This process primarily uses the AWS CLI and CloudFormation, and is automated using a deployment script. For a
real system, a fully automated deployment process would be developed, possibly using the AWS CDK for better control over the stack.

Before deployment, you will need an AWS account, and an S3 bucket to hold the Lambda zip. For further details, see the comments in [deploy.sh](deploy.sh).

## Testing

The code contains integration tests, which would be more full-fledged in a real system. These tests use LocalStack to run DynamoDB locally, enabling the
database code to be properly tested without a full deployment. The `./test-comprehensive.sh` runs a "smoke test" of the deployed application.

## Other Options

This is not the only way this application could be implemented. Adapting the code to run as a servlet container (Tomcat, Jetty) inside a docker container
would allow this to be deployed to Kubernetes or ECS.

## Development Methodology

I didn't actually write any of this code, I used Cursor with claude 4 sonnet. It was quite capable at iterating and fixing the initial issues with setting up
the project, which was most of the work for such a simple project. I then reviewed the code and had Cursor make changes.

## Future Improvements

- Properly wire controller functions to a router
- Write all logs as JSON
- Add SQS dead letter queue for failed transactions
- Fix SnapStart
- Check account balance in controller and transaction processor
