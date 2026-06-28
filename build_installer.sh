#!/bin/bash
# LocoDrive Installer Builder
# This script builds the project, generates a stripped-down custom JRE,
# and packages everything into a native OS installer (deb, exe, or dmg).

set -e

echo "=========================================================="
echo " 1. Building Project & Custom JRE"
echo "=========================================================="
mvn clean package

echo ""
echo "=========================================================="
echo " 2. Preparing Files for jpackage"
echo "=========================================================="
mkdir -p target/pack
cp target/locodrive-*.jar target/pack/

echo ""
echo "=========================================================="
echo " 3. Generating Native Installer"
echo "=========================================================="
# We pass the custom JRE and memory limits to jpackage
jpackage \
  --name LocoDrive \
  --input target/pack/ \
  --main-jar locodrive-1.0.0.jar \
  --main-class com.locodrive.Launcher \
  --runtime-image target/custom-jre \
  --java-options "-Xmx128M -XX:+UseSerialGC -XX:MaxRAM=256M" \
  --dest target/dist

echo ""
echo "=========================================================="
echo " SUCCESS! Your installer is located in the target/dist/ folder."
echo "=========================================================="
