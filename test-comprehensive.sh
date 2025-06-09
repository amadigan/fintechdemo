#!/bin/bash

set -e

# Configuration
AWS_PROFILE="sandbox"
AWS_REGION="eu-west-1"
STACK_NAME="fintechdemo-workflow"

# Debug mode (set to 1 to enable verbose output)
DEBUG=${DEBUG:-1}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_test() {
    echo -e "${YELLOW}[TEST]${NC} $1"
}

print_url() {
    echo -e "${CYAN}[URL]${NC} $1"
}

print_debug() {
    if [ "$DEBUG" = "1" ]; then
        echo -e "${YELLOW}[DEBUG]${NC} $1"
    fi
}

print_log() {
    echo -e "${BLUE}[LOG]${NC} $1"
}

log_request() {
    local method="$1"
    local url="$2"
    local data="$3"
    
    echo ""
    echo "=================================================="
    echo -e "${BLUE}REQUEST:${NC} $method $url"
    if [ ! -z "$data" ]; then
        echo -e "${BLUE}BODY:${NC}"
        echo "$data" | jq . 2>/dev/null || echo "$data"
    fi
    echo "=================================================="
}

log_response() {
    local http_code="$1"
    local response_body="$2"
    
    echo -e "${BLUE}RESPONSE:${NC} HTTP $http_code"
    if [ ! -z "$response_body" ]; then
        echo -e "${BLUE}BODY:${NC}"
        echo "$response_body" | jq . 2>/dev/null || echo "$response_body"
    fi
    echo "=================================================="
    echo ""
}

# Check if required tools are installed
check_dependencies() {
    if ! command -v curl &> /dev/null; then
        print_error "curl is required but not installed"
        exit 1
    fi
    
    if ! command -v jq &> /dev/null; then
        print_error "jq is required but not installed. Please install jq for JSON parsing."
        exit 1
    fi
    
    if ! command -v aws &> /dev/null; then
        print_error "AWS CLI is required but not installed"
        exit 1
    fi
}

# Get API Gateway URL from CloudFormation stack
get_api_url() {
    print_status "Getting API Gateway URL from CloudFormation stack..."

    API_URL=$(aws cloudformation describe-stacks \
        --stack-name "$STACK_NAME" \
        --profile "$AWS_PROFILE" \
        --region "$AWS_REGION" \
        --query 'Stacks[0].Outputs[?OutputKey==`ApiGatewayUrl`].OutputValue' \
        --output text)

    if [ -z "$API_URL" ]; then
        print_error "Could not get API Gateway URL from stack"
        exit 1
    fi

    print_status "API Gateway URL: $API_URL"
}

# Make HTTP request and return response
make_request() {
    local method="$1"
    local url="$2"
    local data="$3"
    
    log_request "$method" "$url" "$data"
    
    if [ "$method" = "POST" ] && [ ! -z "$data" ]; then
        RESPONSE=$(curl -s -w "\n%{http_code}" \
            -X "$method" \
            -H "Content-Type: application/json" \
            -d "$data" \
            "$url")
    else
        RESPONSE=$(curl -s -w "\n%{http_code}" \
            -X "$method" \
            "$url")
    fi
    
    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    RESPONSE_BODY=$(echo "$RESPONSE" | sed '$d')
    
    log_response "$HTTP_CODE" "$RESPONSE_BODY"
    
    # Set global variables for use by caller
    LAST_HTTP_CODE="$HTTP_CODE"
    LAST_RESPONSE_BODY="$RESPONSE_BODY"
}

# Extract value from JSON response
extract_json_value() {
    local json="$1"
    local key="$2"
    echo "$json" | jq -r ".$key" 2>/dev/null
}

# Get the latest log stream for a log group
get_latest_log_stream() {
    local log_group="$1"
    
    aws logs describe-log-streams \
        --log-group-name "$log_group" \
        --order-by LastEventTime \
        --descending \
        --max-items 1 \
        --profile "$AWS_PROFILE" \
        --region "$AWS_REGION" \
        --query 'logStreams[0].logStreamName' \
        --output text 2>/dev/null
}

# Fetch recent log events from a log stream
fetch_recent_logs() {
    local log_group="$1"
    local log_stream="$2"
    local max_lines="${3:-20}"
    
    if [ -z "$log_stream" ] || [ "$log_stream" = "None" ]; then
        print_log "No log stream found for $log_group"
        return
    fi
    
    # Get the most recent events (without time restriction)
    local logs=$(aws logs get-log-events \
        --log-group-name "$log_group" \
        --log-stream-name "$log_stream" \
        --start-from-head false \
        --profile "$AWS_PROFILE" \
        --region "$AWS_REGION" \
        --query "events[-${max_lines}:].message" \
        --output text 2>/dev/null)
    
    if [ ! -z "$logs" ]; then
        echo "$logs" | sed 's/^/    /'
    else
        print_log "No recent log events found"
    fi
}

# Display logs for both Lambda functions
display_lambda_logs() {
    local workflow_log_group="/aws/lambda/fintechdemo-workflow-dev"
    local stream_processor_log_group="/aws/lambda/fintechdemo-stream-processor-dev"
    
    print_test "9. Fetching Lambda function logs..."
    echo ""
    
    # Workflow Lambda logs
    print_log "=== Workflow Lambda Logs ($workflow_log_group) ==="
    local workflow_stream=$(get_latest_log_stream "$workflow_log_group")
    if [ ! -z "$workflow_stream" ] && [ "$workflow_stream" != "None" ]; then
        print_log "Latest log stream: $workflow_stream"
        print_log "Recent log messages:"
        fetch_recent_logs "$workflow_log_group" "$workflow_stream" 15
    else
        print_log "No recent log streams found for workflow lambda"
    fi
    
    echo ""
    
    # Stream Processor Lambda logs  
    print_log "=== Stream Processor Lambda Logs ($stream_processor_log_group) ==="
    local stream_stream=$(get_latest_log_stream "$stream_processor_log_group")
    if [ ! -z "$stream_stream" ] && [ "$stream_stream" != "None" ]; then
        print_log "Latest log stream: $stream_stream"
        print_log "Recent log messages:"
        fetch_recent_logs "$stream_processor_log_group" "$stream_stream" 15
    else
        print_log "No recent log streams found for stream processor lambda"
    fi
    
    echo ""
}

# Main test execution
main() {
    print_status "Starting comprehensive API test..."
    print_status "This script will:"
    print_status "1. Create a customer"
    print_status "2. Create an account for the customer"
    print_status "3. Create 2 deposits and 2 withdrawals"
    print_status "4. Fetch customer accounts"
    print_status "5. Fetch account transactions"
    print_status "6. Fetch transaction CSV export"
    print_status "7. Display recent Lambda function logs"
    echo ""
    
    check_dependencies
    get_api_url
    
    # ========================================
    # 1. Create Customer
    # ========================================
    print_test "1. Creating customer..."
    
    CUSTOMER_DATA='{
        "name": "Test Corporation Ltd"
    }'
    
    make_request "POST" "$API_URL/api/customers" "$CUSTOMER_DATA"
    
    if [ "$LAST_HTTP_CODE" = "201" ]; then
        CUSTOMER_ID=$(extract_json_value "$LAST_RESPONSE_BODY" "id")
        print_status "✓ Customer created successfully"
        print_status "Customer ID: $CUSTOMER_ID"
    else
        print_error "✗ Customer creation failed (HTTP $LAST_HTTP_CODE)"
        exit 1
    fi
    
    # ========================================
    # 2. Create Account
    # ========================================
    print_test "2. Creating account for customer..."
    
    ACCOUNT_DATA='{
        "customerId": "'$CUSTOMER_ID'",
        "name": "Business Current Account",
        "currency": "EUR"
    }'
    
    make_request "POST" "$API_URL/api/accounts" "$ACCOUNT_DATA"
    
    if [ "$LAST_HTTP_CODE" = "201" ]; then
        ACCOUNT_ID=$(extract_json_value "$LAST_RESPONSE_BODY" "id")
        print_status "✓ Account created successfully"
        print_status "Account ID: $ACCOUNT_ID"
    else
        print_error "✗ Account creation failed (HTTP $LAST_HTTP_CODE)"
        exit 1
    fi
    
    # ========================================
    # 3. Create Deposits
    # ========================================
    print_test "3. Creating deposits..."
    
    # First deposit
    DEPOSIT1_DATA='{
        "userId": "user123",
        "currency": "EUR",
        "amount": 5000.00,
        "transactedAt": "2024-12-20T10:00:00Z",
        "payorIBAN": "DE89370400440532013000",
        "originatingCountry": "DE",
        "paymentRef": "Initial deposit",
        "purposeRef": "Account funding"
    }'
    
    make_request "POST" "$API_URL/api/accounts/$ACCOUNT_ID/deposit" "$DEPOSIT1_DATA"
    
    if [ "$LAST_HTTP_CODE" = "201" ]; then
        DEPOSIT1_ID=$(extract_json_value "$LAST_RESPONSE_BODY" "id")
        print_status "✓ First deposit created successfully"
        print_status "Deposit 1 ID: $DEPOSIT1_ID"
    else
        print_error "✗ First deposit creation failed (HTTP $LAST_HTTP_CODE)"
        exit 1
    fi
    
    # Second deposit
    DEPOSIT2_DATA='{
        "userId": "user456",
        "currency": "EUR",
        "amount": 2500.75,
        "transactedAt": "2024-12-20T11:00:00Z",
        "payorIBAN": "FR1420041010050500013M02606",
        "originatingCountry": "FR",
        "paymentRef": "Invoice payment INV-2024-001",
        "purposeRef": "Customer payment"
    }'
    
    make_request "POST" "$API_URL/api/accounts/$ACCOUNT_ID/deposit" "$DEPOSIT2_DATA"
    
    if [ "$LAST_HTTP_CODE" = "201" ]; then
        DEPOSIT2_ID=$(extract_json_value "$LAST_RESPONSE_BODY" "id")
        print_status "✓ Second deposit created successfully"
        print_status "Deposit 2 ID: $DEPOSIT2_ID"
    else
        print_error "✗ Second deposit creation failed (HTTP $LAST_HTTP_CODE)"
        exit 1
    fi
    
    # ========================================
    # 4. Create Withdrawals
    # ========================================
    print_test "4. Creating withdrawals..."
    
    # First withdrawal
    WITHDRAWAL1_DATA='{
        "userId": "user123",
        "currency": "EUR",
        "amount": -1200.50,
        "transactedAt": "2024-12-20T11:30:00Z",
        "beneficiaryIBAN": "GB82WEST12345698765432",
        "originatingCountry": "GB",
        "paymentRef": "Supplier payment SUP-001",
        "purposeRef": "Office supplies"
    }'
    
    make_request "POST" "$API_URL/api/accounts/$ACCOUNT_ID/transaction" "$WITHDRAWAL1_DATA"
    
    if [ "$LAST_HTTP_CODE" = "201" ]; then
        WITHDRAWAL1_ID=$(extract_json_value "$LAST_RESPONSE_BODY" "id")
        print_status "✓ First withdrawal created successfully"
        print_status "Withdrawal 1 ID: $WITHDRAWAL1_ID"
    else
        print_error "✗ First withdrawal creation failed (HTTP $LAST_HTTP_CODE)"
        exit 1
    fi
    
    # Second withdrawal
    WITHDRAWAL2_DATA='{
        "userId": "user789",
        "currency": "EUR",
        "amount": -800.25,
        "transactedAt": "2024-12-20T11:45:00Z",
        "beneficiaryIBAN": "NL91ABNA0417164300",
        "originatingCountry": "NL",
        "paymentRef": "Monthly rent payment",
        "purposeRef": "Office rent"
    }'
    
    make_request "POST" "$API_URL/api/accounts/$ACCOUNT_ID/transaction" "$WITHDRAWAL2_DATA"
    
    if [ "$LAST_HTTP_CODE" = "201" ]; then
        WITHDRAWAL2_ID=$(extract_json_value "$LAST_RESPONSE_BODY" "id")
        print_status "✓ Second withdrawal created successfully"
        print_status "Withdrawal 2 ID: $WITHDRAWAL2_ID"
    else
        print_error "✗ Second withdrawal creation failed (HTTP $LAST_HTTP_CODE)"
        exit 1
    fi
    
    # ========================================
    # 5. Wait for Stream Processing
    # ========================================
    print_test "5. Waiting for stream processing to complete..."
    print_status "Giving the DynamoDB stream time to process transactions..."
    sleep 15
    
    # ========================================
    # 6. Get Customer Accounts
    # ========================================
    print_test "6. Fetching customer accounts..."
    
    ACCOUNTS_URL="$API_URL/api/customers/$CUSTOMER_ID/accounts"
    make_request "GET" "$ACCOUNTS_URL"
    
    if [ "$LAST_HTTP_CODE" = "200" ]; then
        ACCOUNT_COUNT=$(echo "$LAST_RESPONSE_BODY" | jq length)
        print_status "✓ Found $ACCOUNT_COUNT account(s) for customer"
    else
        print_error "✗ Failed to fetch customer accounts (HTTP $LAST_HTTP_CODE)"
    fi
    
    # ========================================
    # 7. Get Account Transactions
    # ========================================
    print_test "7. Fetching account transactions..."
    
    TRANSACTIONS_URL="$API_URL/api/accounts/$ACCOUNT_ID/transactions"
    make_request "GET" "$TRANSACTIONS_URL"
    
    if [ "$LAST_HTTP_CODE" = "200" ]; then
        TRANSACTION_COUNT=$(echo "$LAST_RESPONSE_BODY" | jq '.transactions | length')
        print_status "✓ Found $TRANSACTION_COUNT transaction(s) for account"
    else
        print_error "✗ Failed to fetch account transactions (HTTP $LAST_HTTP_CODE)"
    fi
    
    # ========================================
    # 8. Get Transaction CSV
    # ========================================
    print_test "8. Fetching transaction CSV export..."
    
    CSV_URL="$API_URL/api/accounts/$ACCOUNT_ID/transactions.csv"
    
    log_request "GET" "$CSV_URL" ""
    
    CSV_RESPONSE=$(curl -s -w "\n%{http_code}" "$CSV_URL")
    CSV_HTTP_CODE=$(echo "$CSV_RESPONSE" | tail -n1)
    CSV_BODY=$(echo "$CSV_RESPONSE" | sed '$d')
    
    echo -e "${BLUE}RESPONSE:${NC} HTTP $CSV_HTTP_CODE"
    echo -e "${BLUE}BODY (CSV):${NC}"
    echo "$CSV_BODY"
    echo "=================================================="
    
    if [ "$CSV_HTTP_CODE" = "200" ]; then
        CSV_LINES=$(echo "$CSV_BODY" | wc -l)
        print_status "✓ CSV export successful with $CSV_LINES lines"
    else
        print_error "✗ Failed to fetch CSV export (HTTP $CSV_HTTP_CODE)"
    fi
    
    # ========================================
    # 9. Display Lambda Logs
    # ========================================
    display_lambda_logs
    
    # ========================================
    # 10. Summary and URLs
    # ========================================
    echo ""
    echo "=================================================="
    echo -e "${GREEN}TEST SUMMARY${NC}"
    echo "=================================================="
    print_status "Customer ID: $CUSTOMER_ID"
    print_status "Account ID: $ACCOUNT_ID"
    print_status "Created 2 deposits and 2 withdrawals"
    echo ""
    
    echo -e "${CYAN}Easy Copy URLs:${NC}"
    echo ""
    print_url "Customer details:"
    echo "  $API_URL/api/customers/$CUSTOMER_ID"
    echo ""
    print_url "Customer accounts:"
    echo "  $API_URL/api/customers/$CUSTOMER_ID/accounts"
    echo ""
    print_url "Account details:"
    echo "  $API_URL/api/accounts/$ACCOUNT_ID"
    echo ""
    print_url "Account transactions (JSON):"
    echo "  $API_URL/api/accounts/$ACCOUNT_ID/transactions"
    echo ""
    print_url "Account transactions (CSV):"
    echo "  $API_URL/api/accounts/$ACCOUNT_ID/transactions.csv"
    echo ""
    
    echo -e "${CYAN}Quick Test Commands:${NC}"
    echo ""
    echo "# Get customer accounts:"
    echo "curl '$API_URL/api/customers/$CUSTOMER_ID/accounts' | jq ."
    echo ""
    echo "# Get account transactions:"
    echo "curl '$API_URL/api/accounts/$ACCOUNT_ID/transactions' | jq ."
    echo ""
    echo "# Download CSV:"
    echo "curl '$API_URL/api/accounts/$ACCOUNT_ID/transactions.csv' -o transactions.csv"
    echo ""
    
    print_status "All tests completed successfully! 🎉"
}

# Run main function
main "$@" 
