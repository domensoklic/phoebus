#!/bin/bash
# To include Oracle:
# mkdir -p dependencies/install-jars/lib/ojdbc
# cp /path/to/ojdbc8-12.2.0.1.jar dependencies/install-jars/lib/ojdbc
mvn clean verify  -f dependencies/pom.xml
# All but first run can use -o for offline
#
mvn -DskipTests clean install
