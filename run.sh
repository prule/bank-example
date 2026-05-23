#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

# --- Configuration Constants ---
SWAGGER_URL="http://localhost:8080/swagger-ui/index.html"
H2_CONSOLE_URL="http://localhost:8080/h2-console"
APP_PORT=8080

# --- Helper Functions ---
print_header() {
    echo -e "\n=================================================="
    echo -e " 🚀 $1"
    echo -e "==================================================\n"
}

open_browser() {
    echo "Opening $1 in your default browser..."
    if command -v open &> /dev/null; then
        open "$1" # macOS
    elif command -v xdg-open &> /dev/null; then
        xdg-open "$1" # Linux
    elif command -v start &> /dev/null; then
        start "$1" # Windows / Git Bash
    else
        echo "Could not detect your browser launcher. Please open manually: $1"
    fi
}

wait_for_server() {
    echo -n "Waiting for application to boot on port $APP_PORT..."
    until curl -s "http://localhost:$APP_PORT" > /dev/null || [ $? -ne 7 ]; do
        sleep 1
        echo -n "."
    done
    echo -e "\nServer is up and responding!"
}

# --- Command Targets ---
show_help() {
    echo "Bank Core Management Script"
    echo "Usage: ./run.sh [target]"
    echo ""
    echo "Available Targets:"
    echo "  start-sync     Starts the server using the SYNCHRONOUS ledger engine"
    echo "  start-async    Starts the server using the ASYNCHRONOUS_EVENT outbox engine"
    echo "  start-batch    Starts the server using the BATCH_RECONCILIATION engine"
    echo "  test           Cleans the build cache and runs all integration test suites"
    echo "  swagger        Launches the Swagger UI in your default browser"
    echo "  h2             Launches the H2 Database Web Console in your default browser"
    echo "  help           Displays this help options matrix"
    echo ""
}

case "$1" in
    start-sync)
        print_header "Starting Server: SYNCHRONOUS Strategy"
        ./gradlew openApiGenerate compileJava
        LEDGER_STRATEGY=SYNCHRONOUS SEED_DATA=true ./gradlew bootRun
        ;;
    start-async)
        print_header "Starting Server: ASYNCHRONOUS_EVENT Strategy"
        ./gradlew openApiGenerate compileJava
        LEDGER_STRATEGY=ASYNCHRONOUS_EVENT SEED_DATA=true ./gradlew bootRun
        ;;
    start-batch)
        print_header "Starting Server: BATCH_RECONCILIATION Strategy"
        ./gradlew openApiGenerate compileJava
        LEDGER_STRATEGY=BATCH_RECONCILIATION SEED_DATA=true ./gradlew bootRun
        ;;
    test)
        print_header "Executing Test Suites"
        ./gradlew clean test --info
        ;;
    swagger)
        print_header "Launching API Documentation Console"
        if ! curl -s "http://localhost:$APP_PORT" > /dev/null; then
            echo "Server is not currently running. Attempting to track server boot..."
            wait_for_server
        fi
        open_browser "$SWAGGER_URL"
        ;;
    h2)
        print_header "Launching H2 Database Web UI"
        if ! curl -s "http://localhost:$APP_PORT" > /dev/null; then
            echo "Server is not currently running. Attempting to track server boot..."
            wait_for_server
        fi

        echo -e "\n--------------------------------------------------"
        echo -e "💡  H2 Console Login Credentials:"
        echo -e "    JDBC URL:  jdbc:h2:mem:bank_core_dev"
        echo -e "    User Name: sa"
        echo -e "    Password:  password"
        echo -e "--------------------------------------------------\n"

        open_browser "$H2_CONSOLE_URL"
        ;;
    help|*)
        show_help
        ;;
esac