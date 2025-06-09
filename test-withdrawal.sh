#!/bin/bash

set -e

# Configuration
ACCOUNT_ID="48d6f9c8-7370-4ed0-a8b0-342fbdcaf9bd"

# Colors for output
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
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

print_response() {
    echo -e "${BLUE}[RESPONSE]${NC} $1"
}

# Hardcoded API Gateway URL (no AWS CLI dependency)
API_URL="https://62c892163g.execute-api.eu-west-1.amazonaws.com/dev"
print_status "Using API URL: $API_URL"

# Check dependencies
if ! command -v curl >/dev/null 2>&1; then
    print_error "curl is required but not installed"
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    print_error "jq is required but not installed"
    exit 1
fi

print_status "Starting withdrawal test for account: $ACCOUNT_ID"
echo ""

# ========================================
# 1. Submit the example withdrawal from requirements.md
# ========================================
print_test "1. Submitting example withdrawal from requirements.md..."

# Example JSON from requirements.md (modified for withdrawal - negative amount)
WITHDRAWAL_DATA='{
    "userId": "134256",
    "currency": "EUR", 
    "amount": -1000,
    "transactedAt": "2024-12-20T10:27:44Z",
    "beneficiaryIBAN": "",
    "originatingCountry": "FR",
    "paymentRef": "Invoice nr 12345",
    "purposeRef": "invoice payment"
}'

echo "Request JSON:"
echo "$WITHDRAWAL_DATA" | jq .
echo ""

# Submit withdrawal
WITHDRAWAL_URL="$API_URL/api/accounts/$ACCOUNT_ID/transaction"
RESPONSE=$(curl -s -w "\n%{http_code}" \
    -X POST \
    -H "Content-Type: application/json" \
    -d "$WITHDRAWAL_DATA" \
    "$WITHDRAWAL_URL")

HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
RESPONSE_BODY=$(echo "$RESPONSE" | sed '$d')

print_response "HTTP Status: $HTTP_CODE"
if [[ -n "$RESPONSE_BODY" ]]; then
    echo "Response Body:"
    echo "$RESPONSE_BODY" | jq . 2>/dev/null || echo "$RESPONSE_BODY"
fi
echo ""

if [[ "$HTTP_CODE" == "201" ]]; then
    TRANSACTION_ID=$(echo "$RESPONSE_BODY" | jq -r '.id' 2>/dev/null)
    print_status "✓ Withdrawal submitted successfully"
    if [[ "$TRANSACTION_ID" != "null" ]] && [[ -n "$TRANSACTION_ID" ]]; then
        print_status "Transaction ID: $TRANSACTION_ID"
    fi
else
    print_error "✗ Withdrawal submission failed (HTTP $HTTP_CODE)"
    echo "Response: $RESPONSE_BODY"
    exit 1
fi

# ========================================
# 2. Wait for processing
# ========================================
print_test "2. Waiting for transaction processing..."
sleep 5

# ========================================
# 3. Get transactions as JSON
# ========================================
print_test "3. Fetching account transactions (JSON)..."

JSON_URL="$API_URL/api/accounts/$ACCOUNT_ID/transactions"
JSON_RESPONSE=$(curl -s -w "\n%{http_code}" "$JSON_URL")

JSON_HTTP_CODE=$(echo "$JSON_RESPONSE" | tail -n1)
JSON_BODY=$(echo "$JSON_RESPONSE" | sed '$d')

print_response "HTTP Status: $JSON_HTTP_CODE"
echo ""

if [[ "$JSON_HTTP_CODE" == "200" ]]; then
    print_status "✓ Successfully fetched transactions (JSON)"
    echo ""
    echo "=================================="
    echo "TRANSACTIONS (JSON):"
    echo "=================================="
    echo "$JSON_BODY" | jq . 2>/dev/null || echo "$JSON_BODY"
    echo ""
    
    # Count transactions
    if command -v jq >/dev/null 2>&1; then
        TRANSACTION_COUNT=$(echo "$JSON_BODY" | jq '.transactions | length' 2>/dev/null || echo "unknown")
        print_status "Total transactions: $TRANSACTION_COUNT"
    fi
else
    print_error "✗ Failed to fetch JSON transactions (HTTP $JSON_HTTP_CODE)"
    echo "Response: $JSON_BODY"
fi

echo ""

# ========================================
# 4. Get transactions as CSV
# ========================================
print_test "4. Fetching account transactions (CSV)..."

CSV_URL="$API_URL/api/accounts/$ACCOUNT_ID/transactions.csv"
CSV_RESPONSE=$(curl -s -w "\n%{http_code}" "$CSV_URL")

CSV_HTTP_CODE=$(echo "$CSV_RESPONSE" | tail -n1)
CSV_BODY=$(echo "$CSV_RESPONSE" | sed '$d')

print_response "HTTP Status: $CSV_HTTP_CODE"
echo ""

if [[ "$CSV_HTTP_CODE" == "200" ]]; then
    print_status "✓ Successfully fetched transactions (CSV)"
    echo ""
    echo "=================================="
    echo "TRANSACTIONS (CSV):"
    echo "=================================="
    echo "$CSV_BODY"
    echo ""
    
    # Count CSV lines
    CSV_LINES=$(echo "$CSV_BODY" | wc -l | tr -d ' ')
    DATA_LINES=$((CSV_LINES - 1))  # Subtract header
    print_status "CSV lines: $CSV_LINES (including header)"
    print_status "Data rows: $DATA_LINES"
else
    print_error "✗ Failed to fetch CSV transactions (HTTP $CSV_HTTP_CODE)"
    echo "Response: $CSV_BODY"
fi

echo ""

# ========================================
# 5. Summary
# ========================================
echo "=================================="
echo "SUMMARY:"
echo "=================================="
print_status "Account ID: $ACCOUNT_ID"
print_status "API Base URL: $API_URL"
echo ""
echo "Quick access URLs:"
echo "- JSON transactions: $JSON_URL"
echo "- CSV transactions: $CSV_URL"
echo ""
print_status "Test completed! 🎉" 
