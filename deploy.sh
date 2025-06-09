#!/bin/bash

set -e

# Configuration
AWS_PROFILE="sandbox"
AWS_REGION="eu-west-1"
S3_BUCKET="amadigan-deploy-68883f54"
STACK_NAME="fintechdemo-workflow"
ENVIRONMENT="dev"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_status() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if AWS profile exists
if ! aws configure list-profiles | grep -q "^${AWS_PROFILE}$"; then
    print_error "AWS profile '${AWS_PROFILE}' not found"
    exit 1
fi

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    print_error "Maven is not installed"
    exit 1
fi

print_status "Building Lambda package..."

# Clean and build the project
mvn clean package -DskipTests

# Check if the JAR was built successfully
JAR_FILE="target/fintechdemo-workflow-lambda.jar"
if [ ! -f "$JAR_FILE" ]; then
    print_error "Build failed - JAR file not found: $JAR_FILE"
    exit 1
fi

print_status "JAR file built successfully: $JAR_FILE"

# Get JAR file size
JAR_SIZE=$(stat -f%z "$JAR_FILE" 2>/dev/null || stat -c%s "$JAR_FILE" 2>/dev/null)
print_status "JAR file size: $(( JAR_SIZE / 1024 / 1024 )) MB"

# Upload to S3
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
S3_KEY="fintechdemo-workflow-lambda-${TIMESTAMP}.jar"
print_status "Uploading to S3: s3://${S3_BUCKET}/${S3_KEY}"

aws s3 cp "$JAR_FILE" "s3://${S3_BUCKET}/${S3_KEY}" \
    --profile "$AWS_PROFILE" \
    --region "$AWS_REGION"

if [ $? -eq 0 ]; then
    print_status "Successfully uploaded to S3"
else
    print_error "Failed to upload to S3"
    exit 1
fi

# Check if CloudFormation stack exists
print_status "Checking if CloudFormation stack exists..."

if aws cloudformation describe-stacks \
    --stack-name "$STACK_NAME" \
    --profile "$AWS_PROFILE" \
    --region "$AWS_REGION" \
    --output text \
    --query 'Stacks[0].StackStatus' 2>/dev/null; then
    
    print_status "Stack exists. Updating..."
    OPERATION="update-stack"
    OPERATION_VERB="updating"
else
    print_status "Stack does not exist. Creating..."
    OPERATION="create-stack"
    OPERATION_VERB="creating"
fi

# Deploy or update CloudFormation stack
print_status "Starting CloudFormation stack ${OPERATION_VERB}..."

aws cloudformation ${OPERATION} \
    --stack-name "$STACK_NAME" \
    --template-body file://infrastructure/cloudformation-stack.yaml \
    --parameters \
        ParameterKey=Environment,ParameterValue="$ENVIRONMENT" \
        ParameterKey=LambdaCodeBucket,ParameterValue="$S3_BUCKET" \
        ParameterKey=LambdaCodeKey,ParameterValue="$S3_KEY" \
    --capabilities CAPABILITY_NAMED_IAM \
    --profile "$AWS_PROFILE" \
    --region "$AWS_REGION"

if [ $? -eq 0 ]; then
    print_status "CloudFormation stack operation initiated successfully"
    
    # Wait for stack operation to complete
    print_status "Waiting for stack operation to complete..."
    
    if [ "$OPERATION" = "create-stack" ]; then
        aws cloudformation wait stack-create-complete \
            --stack-name "$STACK_NAME" \
            --profile "$AWS_PROFILE" \
            --region "$AWS_REGION"
    else
        aws cloudformation wait stack-update-complete \
            --stack-name "$STACK_NAME" \
            --profile "$AWS_PROFILE" \
            --region "$AWS_REGION"
    fi
    
    if [ $? -eq 0 ]; then
        print_status "Stack operation completed successfully"
        
        # Get stack outputs
        print_status "Getting stack outputs..."
        
        OUTPUTS=$(aws cloudformation describe-stacks \
            --stack-name "$STACK_NAME" \
            --profile "$AWS_PROFILE" \
            --region "$AWS_REGION" \
            --query 'Stacks[0].Outputs' \
            --output table)
        
        echo ""
        echo "=== DEPLOYMENT SUCCESSFUL ==="
        echo "$OUTPUTS"
        echo ""
        
        # Extract specific URLs
        API_URL=$(aws cloudformation describe-stacks \
            --stack-name "$STACK_NAME" \
            --profile "$AWS_PROFILE" \
            --region "$AWS_REGION" \
            --query 'Stacks[0].Outputs[?OutputKey==`ApiGatewayUrl`].OutputValue' \
            --output text)
        
        S3_WEBSITE_URL=$(aws cloudformation describe-stacks \
            --stack-name "$STACK_NAME" \
            --profile "$AWS_PROFILE" \
            --region "$AWS_REGION" \
            --query 'Stacks[0].Outputs[?OutputKey==`StaticContentBucketWebsiteURL`].OutputValue' \
            --output text)
        
        if [ ! -z "$API_URL" ]; then
            print_status "API Gateway URL: $API_URL"
            print_status "Test customer endpoint: $API_URL/api/customers"
        fi
        
        if [ ! -z "$S3_WEBSITE_URL" ]; then
            print_status "S3 Website URL: $S3_WEBSITE_URL"
            print_status "Static content will be served from: $S3_WEBSITE_URL"
        fi
        
    else
        print_error "Stack operation failed"
        exit 1
    fi
else
    print_error "Failed to initiate CloudFormation stack operation"
    exit 1
fi

print_status "Deployment completed successfully!" 
