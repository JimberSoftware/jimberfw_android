#!/bin/bash

# Define arrays for source and target strings
source_strings=(".com.jimberisolation")

target_strings=(".com/wireguard")


#.com.jimberisolation
#".com/wireguard

# Function to replace the source strings with the corresponding target strings in files
replace_in_files() {
    local file="$1"
    local script_filename="replace-text.sh"
    local num_mappings=${#source_strings[@]}
    
    # Check if the file is the script itself and skip replacement if true
    if [ "$(basename "$file")" != "$script_filename" ]; then
        # Loop through source_strings and replace each occurrence with the corresponding target string
        for ((i=0; i<num_mappings; i++)); do
            sed -i "s/${source_strings[$i]}/${target_strings[$i]}/g" "$file"
        done
        echo "Replaced text in: $file"
    else
        echo "Skipped processing of the script: $file"
    fi
}

# Recursive function to traverse directories and replace text in files
traverse_and_replace() {
    local dir="$1"
    # Loop through files and directories in the current directory
    for file in "$dir"/*; do
        if [ -f "$file" ]; then
            # If it's a file, replace text in the file
            replace_in_files "$file"
        elif [ -d "$file" ]; then
            # If it's a directory, recursively call the function for the directory
            traverse_and_replace "$file"
        fi
    done
}

# Start traversing from the current directory
traverse_and_replace "."

echo "Text replacement completed successfully."