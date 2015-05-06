#!/bin/sh
JAVA=`which java`
echo "Using Java: $JAVA"
$JAVA -Djava.util.logging.config.file=log.properties -jar HeliosKwlRemote-1.0.0-SNAPSHOT-jar-with-dependencies.jar 
