#!/bin/bash
set -e

# Find the binary (case-insensitive, exclude shared libs)
BINARY=$(find target/gluonfx -type f -executable ! -name "*.so" ! -name "*.o" ! -name "*.a" | head -n 1)

if [ -z "$BINARY" ]; then
    echo "❌ Error: Native binary not found!"
    exit 1
fi

echo "Setting up AppDir..."
mkdir -p AppDir/usr/bin
cp "$BINARY" AppDir/usr/bin/locodrive
chmod +x AppDir/usr/bin/locodrive

# Copy icon
if [ -f "src/main/resources/images/app-icon.png" ]; then
    cp src/main/resources/images/app-icon.png AppDir/locodrive.png
else
    touch AppDir/locodrive.png
fi

# Create .desktop file
cat << 'EOF' > AppDir/locodrive.desktop
[Desktop Entry]
Name=LocoDrive
Exec=locodrive
Icon=locodrive
Type=Application
Categories=Utility;Network;
EOF

# Create AppRun
cat << 'EOF' > AppDir/AppRun
#!/bin/sh
HERE="$(dirname "$(readlink -f "${0}")")"
exec "${HERE}/usr/bin/locodrive" "$@"
EOF
chmod +x AppDir/AppRun

# Download appimagetool
wget -q "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
chmod +x appimagetool-x86_64.AppImage

# Build AppImage
echo "Building AppImage..."
./appimagetool-x86_64.AppImage --appimage-extract-and-run AppDir

mkdir -p dist
mv LocoDrive-x86_64.AppImage dist/
echo "✅ AppImage created in dist/"
