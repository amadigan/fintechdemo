#!/bin/bash

set -e

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

print_warning() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# Check if Maven is installed
if ! command -v mvn &> /dev/null; then
    print_error "Maven is not installed"
    exit 1
fi

print_status "Cleaning previous builds..."
mvn clean

print_status "Building Lambda deployment package..."
mvn package -DskipTests

# Check if the JAR was built successfully
JAR_FILE="target/fintechdemo-workflow-lambda.jar"
if [ ! -f "$JAR_FILE" ]; then
    print_error "Build failed - JAR file not found: $JAR_FILE"
    exit 1
fi

print_status "✓ Lambda JAR package built successfully: $JAR_FILE"

# Get JAR file size
JAR_SIZE=$(stat -f%z "$JAR_FILE" 2>/dev/null || stat -c%s "$JAR_FILE" 2>/dev/null)
print_status "JAR file size: $(( JAR_SIZE / 1024 / 1024 )) MB"

print_status "Build completed successfully!"
print_warning "Remember to upload this JAR file to S3 before deploying the Lambda function." 
