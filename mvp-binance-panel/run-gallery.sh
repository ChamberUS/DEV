#!/bin/zsh
# DEV ONLY: BYX V2 control gallery (every control state in FULL / REDUCED / OFF).
cd "$(dirname "$0")"
export JAVA_HOME="$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
mvn -q javafx:run -Djavafx.args="--gallery"
