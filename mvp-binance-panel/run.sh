#!/bin/zsh
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
mvn -q javafx:run
