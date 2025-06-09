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

print_debug() {
    if [ "$DEBUG" = "1" ]; then
        echo -e "${YELLOW}[DEBUG]${NC} $1"
    fi
}

debug_curl() {
    local method="$1"
    local url="$2"
    local data="$3"
    local headers="$4"
    
    print_debug "Making $method request to: $url"
    if [ ! -z "$data" ]; then
        print_debug "Request body: $data"
    fi
    if [ ! -z "$headers" ]; then
        print_debug "Headers: $headers"
    fi
}

# Get API Gateway URL from CloudFormation stack
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

# Test customer creation
print_test "Testing customer creation..."

CUSTOMER_DATA='{
    "name": "Leviathan SA"
}'

debug_curl "POST" "$API_URL/api/customers" "$CUSTOMER_DATA" "Content-Type: application/json"

CUSTOMER_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$CUSTOMER_DATA" \
    "$API_URL/api/customers")

CUSTOMER_HTTP_CODE=$(echo "$CUSTOMER_RESPONSE" | tail -n1)
CUSTOMER_BODY=$(echo "$CUSTOMER_RESPONSE" | sed '$d')

print_debug "Raw curl response: $CUSTOMER_RESPONSE"
print_debug "Parsed HTTP code: $CUSTOMER_HTTP_CODE"
print_debug "Parsed body: $CUSTOMER_BODY"

echo "HTTP Status: $CUSTOMER_HTTP_CODE"
echo "Response: $CUSTOMER_BODY"

if [ "$CUSTOMER_HTTP_CODE" = "201" ]; then
    print_status "✓ Customer creation test passed"
    
    # Extract customer ID for subsequent tests (UUID format)
    CUSTOMER_ID=$(echo "$CUSTOMER_BODY" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
    print_status "Created customer ID: $CUSTOMER_ID"
else
    print_error "✗ Customer creation test failed"
fi

echo ""

# Test getting customer
if [ ! -z "$CUSTOMER_ID" ]; then
    print_test "Testing get customer..."
    
    GET_CUSTOMER_RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X GET \
        "$API_URL/api/customers/$CUSTOMER_ID")
    
    GET_CUSTOMER_HTTP_CODE=$(echo "$GET_CUSTOMER_RESPONSE" | tail -n1)
    GET_CUSTOMER_BODY=$(echo "$GET_CUSTOMER_RESPONSE" | sed '$d')
    
    echo "HTTP Status: $GET_CUSTOMER_HTTP_CODE"
    echo "Response: $GET_CUSTOMER_BODY"
    
    if [ "$GET_CUSTOMER_HTTP_CODE" = "200" ]; then
        print_status "✓ Get customer test passed"
    else
        print_error "✗ Get customer test failed"
    fi
    
    echo ""
fi

# Test account creation
print_test "Testing account creation..."

# Use customer ID if available, otherwise use a sample UUID
CUSTOMER_ID_FOR_ACCOUNT=${CUSTOMER_ID:-"550e8400-e29b-41d4-a716-446655440000"}

ACCOUNT_DATA='{
    "customerId": "'${CUSTOMER_ID_FOR_ACCOUNT}'",
    "currency": "USD"
}'

ACCOUNT_RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$ACCOUNT_DATA" \
    "$API_URL/api/accounts")

ACCOUNT_HTTP_CODE=$(echo "$ACCOUNT_RESPONSE" | tail -n1)
ACCOUNT_BODY=$(echo "$ACCOUNT_RESPONSE" | sed '$d')

echo "HTTP Status: $ACCOUNT_HTTP_CODE"
echo "Response: $ACCOUNT_BODY"

if [ "$ACCOUNT_HTTP_CODE" = "201" ]; then
    print_status "✓ Account creation test passed"
    
    # Extract account ID
    ACCOUNT_ID=$(echo "$ACCOUNT_BODY" | grep -o '"id":"[^"]*"' | cut -d'"' -f4)
    print_status "Created account ID: $ACCOUNT_ID"
else
    print_error "✗ Account creation test failed"
fi

echo ""

# Test getting account
if [ ! -z "$ACCOUNT_ID" ]; then
    print_test "Testing get account..."
    
    GET_ACCOUNT_RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X GET \
        "$API_URL/api/accounts/$ACCOUNT_ID")
    
    GET_ACCOUNT_HTTP_CODE=$(echo "$GET_ACCOUNT_RESPONSE" | tail -n1)
    GET_ACCOUNT_BODY=$(echo "$GET_ACCOUNT_RESPONSE" | sed '$d')
    
    echo "HTTP Status: $GET_ACCOUNT_HTTP_CODE"
    echo "Response: $GET_ACCOUNT_BODY"
    
    if [ "$GET_ACCOUNT_HTTP_CODE" = "200" ]; then
        print_status "✓ Get account test passed"
    else
        print_error "✗ Get account test failed"
    fi
    
    echo ""
fi

# Test getting customer accounts
if [ ! -z "$CUSTOMER_ID" ]; then
    print_test "Testing get customer accounts..."
    
    ACCOUNTS_URL="$API_URL/api/customers/$CUSTOMER_ID/accounts"
    debug_curl "GET" "$ACCOUNTS_URL" "" ""
    
    GET_ACCOUNTS_RESPONSE=$(curl -s -w "\n%{http_code}" \
        -X GET \
        "$ACCOUNTS_URL")
    
    GET_ACCOUNTS_HTTP_CODE=$(echo "$GET_ACCOUNTS_RESPONSE" | tail -n1)
    GET_ACCOUNTS_BODY=$(echo "$GET_ACCOUNTS_RESPONSE" | sed '$d')
    
    print_debug "Raw accounts response: $GET_ACCOUNTS_RESPONSE"
    print_debug "Parsed HTTP code: $GET_ACCOUNTS_HTTP_CODE"
    print_debug "Parsed body: $GET_ACCOUNTS_BODY"
    
    echo "HTTP Status: $GET_ACCOUNTS_HTTP_CODE"
    echo "Response: $GET_ACCOUNTS_BODY"
    
    if [ "$GET_ACCOUNTS_HTTP_CODE" = "200" ]; then
        print_status "✓ Get customer accounts test passed"
    else
        print_error "✗ Get customer accounts test failed"
        print_debug "Expected 200 but got $GET_ACCOUNTS_HTTP_CODE for URL: $ACCOUNTS_URL"
    fi
fi

print_status "API testing completed!" 
