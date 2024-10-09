#!/bin/bash

# Check if exactly one argument is provided
if [ "$#" -ne 1 ]; then
    echo "Usage: $0 {staging|production}"
    exit 1
fi

# Assign the first argument to the ENVIRONMENT variable
ENVIRONMENT=$1

# Validate the argument
if [[ "$ENVIRONMENT" != "staging" && "$ENVIRONMENT" != "production" ]]; then
    echo "Error: Argument must be 'staging' or 'production'."
    exit 1
fi

# Perform actions based on the environment
if [ "$ENVIRONMENT" = "staging" ]; then
    echo "Deploying to the staging environment..."
    cp ./environments/staging/AndroidManifest.xml ./ui/src/main/AndroidManifest.xml
    cp ./environments/staging/Config.kt ./ui/src/main/java/com/jimberisolation/android/configStore
    cp ./environments/staging/msal_config.json ./ui/src/main/res/raw

    echo "Done"

elif [ "$ENVIRONMENT" = "production" ]; then
    echo "Changing to the production environment..."
    cp ./environments/production/AndroidManifest.xml ./ui/src/main/AndroidManifest.xml
    cp ./environments/production/Config.kt ./ui/src/main/java/com/jimberisolation/android/configStore
    cp ./environments/production/msal_config.json ./ui/src/main/res/raw

    echo "Done"
fi
