#!/bin/bash
# Double-click on macOS (or run ./run.command) to start the CrisisConnect desktop app + web portal.
cd "$(dirname "$0")"
if ! command -v mvn >/dev/null 2>&1; then
  echo "Maven is not installed. Install it with:  brew install maven openjdk@21"
  read -p "Press Enter to close..."; exit 1
fi
echo "Starting CrisisConnect...  (website will be live at http://localhost:8080)"
mvn -q javafx:run
