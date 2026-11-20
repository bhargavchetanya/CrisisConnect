#!/bin/bash
# Starts ONLY the REST API + website (no JavaFX window). Then open http://localhost:8080
cd "$(dirname "$0")"
mvn -q compile exec:java
