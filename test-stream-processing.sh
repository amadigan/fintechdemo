#!/bin/bash

echo "=== Testing Stream Processing Manually ==="

# Create a simple DynamoDB event payload for testing
cat > test-stream-event.json << 'EOF'
{
  "Records": [
    {
      "eventID": "test-event-1",
      "eventName": "INSERT",
      "eventVersion": "1.1",
      "eventSource": "aws:dynamodb",
      "awsRegion": "eu-west-1",
      "dynamodb": {
        "ApproximateCreationDateTime": 1.0,
        "Keys": {
          "id": {
            "S": "362247d8-6840-475f-8b76-71d6e3f0ee4a"
          }
        },
        "NewImage": {
          "id": {
            "S": "362247d8-6840-475f-8b76-71d6e3f0ee4a"
          },
          "type": {
            "S": "TRANSACTION"
          },
          "sequence": {
            "S": "pending-1f044bf4-1bd9-6087-bfd7-713d8be07fb7"
          },
          "accountId": {
            "S": "123e4567-e89b-12d3-a456-426614174001"
          },
          "amount": {
            "N": "777.77"
          }
        },
        "StreamViewType": "NEW_AND_OLD_IMAGES",
        "SequenceNumber": "111",
        "SizeBytes": 26
      }
    }
  ]
}
EOF

echo "1. Checking if stream processor function exists..."
if aws lambda get-function --function-name fintechdemo-stream-processor-dev --profile sandbox --region eu-west-1 > /dev/null 2>&1; then
    echo "✅ Stream processor function exists"
else
    echo "❌ Stream processor function does not exist!"
    echo "Listing all Lambda functions starting with 'fintechdemo':"
    aws lambda list-functions --profile sandbox --region eu-west-1 --query 'Functions[?starts_with(FunctionName, `fintechdemo`)].FunctionName'
    exit 1
fi

echo ""
echo "2. Testing manual invocation of stream processor..."
aws lambda invoke \
    --function-name fintechdemo-stream-processor-dev \
    --payload fileb://test-stream-event.json \
    --profile sandbox \
    --region eu-west-1 \
    response.json

echo ""
echo "3. Response from manual invocation:"
cat response.json
echo ""

echo ""
echo "4. Checking recent CloudWatch logs for stream processor..."
LOG_GROUP="/aws/lambda/fintechdemo-stream-processor-dev"
if aws logs describe-log-groups --log-group-name-prefix "$LOG_GROUP" --profile sandbox --region eu-west-1 > /dev/null 2>&1; then
    echo "Getting recent log events..."
    aws logs describe-log-streams \
        --log-group-name "$LOG_GROUP" \
        --profile sandbox \
        --region eu-west-1 \
        --order-by LastEventTime \
        --descending \
        --max-items 1 \
        --query 'logStreams[0].logStreamName' \
        --output text > latest_stream.txt
    
    LATEST_STREAM=$(cat latest_stream.txt)
    if [ "$LATEST_STREAM" != "None" ] && [ -n "$LATEST_STREAM" ]; then
        echo "Latest log stream: $LATEST_STREAM"
        aws logs get-log-events \
            --log-group-name "$LOG_GROUP" \
            --log-stream-name "$LATEST_STREAM" \
            --profile sandbox \
            --region eu-west-1 \
            --query 'events[-10:].message' \
            --output text
    else
        echo "No log streams found"
    fi
else
    echo "❌ No log group found for stream processor"
fi

echo ""
echo "5. Checking current transaction status..."
API_BASE="https://zq1oplglde.execute-api.eu-west-1.amazonaws.com/dev"
TRANSACTIONS=$(curl -s -X GET "${API_BASE}/api/accounts/123e4567-e89b-12d3-a456-426614174001/transactions")

if echo "$TRANSACTIONS" | grep -q "transaction-"; then
    echo "✅ Found processed transactions:"
    echo "$TRANSACTIONS" | grep -o '"sequence":"transaction-[^"]*"' | head -3
else
    echo "❌ All transactions still have pending sequences:"
    echo "$TRANSACTIONS" | grep -o '"sequence":"pending-[^"]*"' | head -3
fi

# Cleanup
rm -f test-stream-event.json response.json latest_stream.txt

echo ""
echo "=== Test Complete ===" 
