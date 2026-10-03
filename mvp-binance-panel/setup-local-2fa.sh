#!/bin/zsh
set -eu
set +x
umask 077
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
mvn -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/setup-classpath.txt
exec "$JAVA_HOME/bin/java" -cp "target/classes:$(cat target/setup-classpath.txt)" panel.security.Local2faSetup
