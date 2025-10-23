#!/bin/bash

# ==============================================================================
# Remove ALL Koppeltaal/VZVZ Package Resources from HAPI FHIR Database
# ==============================================================================
# Purpose: Wrapper script to safely remove all Koppeltaal/VZVZ package resources
#          to allow search index restoration
# ==============================================================================

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${BLUE}$1${NC}"
}

print_success() {
    echo -e "${GREEN}$1${NC}"
}

print_warning() {
    echo -e "${YELLOW}$1${NC}"
}

print_error() {
    echo -e "${RED}$1${NC}"
}

# Check if required arguments are provided
if [ $# -lt 4 ]; then
    print_error "Usage: $0 <host> <port> <username> <database>"
    print_info "Example: $0 35.204.98.239 5432 hapi_fhir_server_staging hapi_fhir_server_staging"
    print_info ""
    print_info "Note: Set PGPASSWORD environment variable before running this script"
    print_info "      export PGPASSWORD=your_password"
    exit 1
fi

DB_HOST=$1
DB_PORT=$2
DB_USER=$3
DB_NAME=$4

# Check if PGPASSWORD is set
if [ -z "$PGPASSWORD" ]; then
    print_error "ERROR: PGPASSWORD environment variable is not set"
    print_info "Please set it before running this script:"
    print_info "  export PGPASSWORD=your_password"
    exit 1
fi

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
SQL_FILE="$SCRIPT_DIR/remove-ig-from-database.sql"

# Check if SQL file exists
if [ ! -f "$SQL_FILE" ]; then
    print_error "ERROR: SQL file not found: $SQL_FILE"
    exit 1
fi

print_info "======================================================================"
print_info "  HAPI FHIR - Remove ALL Koppeltaal/VZVZ Package Resources"
print_info "======================================================================"
print_info "Database Host: $DB_HOST"
print_info "Database Port: $DB_PORT"
print_info "Database User: $DB_USER"
print_info "Database Name: $DB_NAME"
print_info "SQL File:      $SQL_FILE"
print_info "======================================================================"
print_warning ""
print_warning "WARNING: This script will DELETE ALL Koppeltaal/VZVZ resources"
print_warning "         from the database, including:"
print_warning "           - ImplementationGuide"
print_warning "           - StructureDefinitions (profiles, extensions)"
print_warning "           - CodeSystems"
print_warning "           - ValueSets"
print_warning "           - SearchParameters"
print_warning "           - And any other Koppeltaal/VZVZ resources"
print_warning ""
print_warning "         This operation cannot be undone."
print_warning ""
print_warning "RECOMMENDATION: Create a database backup before proceeding."
print_warning ""

# Ask for confirmation unless --yes flag is provided
if [ "$5" != "--yes" ] && [ "$5" != "-y" ]; then
    read -p "$(echo -e ${YELLOW}Do you want to proceed? [yes/no]:${NC} )" -r
    echo
    if [[ ! $REPLY =~ ^[Yy]es$ ]]; then
        print_info "Aborted by user."
        exit 0
    fi
fi

print_info ""
print_info "Connecting to database and executing removal script..."
print_info ""

# Execute the SQL script
if psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SQL_FILE"; then
    print_success ""
    print_success "======================================================================"
    print_success "  All Koppeltaal/VZVZ resources successfully removed from database"
    print_success "======================================================================"
    print_info ""
    print_info "Next steps:"
    print_info "  1. Restore/rebuild the search index"
    print_info "  2. Re-upload the package using sync-fhir-package.py:"
    print_info ""
    print_info "     python3 scripts/sync-fhir-package.py sync \\"
    print_info "       https://your-fhir-server.com/fhir/DEFAULT \\"
    print_info "       https://github.com/vzvznl/Koppeltaal-2.0-FHIR/releases/download/v0.15.0-beta.7d/koppeltaalv2-0.15.0-beta.7d-minimal.tgz \\"
    print_info "       --yes"
    print_info ""
else
    print_error ""
    print_error "======================================================================"
    print_error "  ERROR: Failed to remove Koppeltaal/VZVZ resources"
    print_error "======================================================================"
    print_error ""
    print_error "Please check the error messages above for details."
    exit 1
fi
