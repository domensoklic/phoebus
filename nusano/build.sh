#!/usr/bin/env bash
TOP=$(pwd)/../
echo "TOP is: $TOP"

# CD to to top directory
cd $TOP
echo CWD: `pwd`

# Apply Nusano specific build patches patches
for patch in nusano/patches/*
do
    echo "Applying patch: $patch"
    git apply --whitespace=fix $patch
done

# Build
echo "Building ..."
mvn clean verify -f dependencies/pom.xml
ant clean dist
