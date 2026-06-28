#!/bin/bash
# LocoDrive GraalVM Native Installer Builder
# This script uses the GluonFX plugin to compile your Java application
# directly into Native Machine Code via GraalVM Ahead-Of-Time compilation.
# NOTE: You MUST have GraalVM and the native C++ toolchain (MSVC/XCode/GCC) installed!

set -e

echo "=========================================================="
echo " Building Native Machine Code via GraalVM (This takes 5-15 mins!)"
echo "=========================================================="
mvn clean gluonfx:build gluonfx:package

echo ""
echo "=========================================================="
echo " SUCCESS! Your standalone native installer is located in target/gluonfx/"
echo "=========================================================="
