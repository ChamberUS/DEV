#!/bin/zsh
set -e
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
qa_output="${1:-$(mktemp -d /tmp/byx-ui-qa.XXXXXX)}"
qa_classpath="$(mktemp /tmp/byx-ui-classpath.XXXXXX)"
trap 'rm -f "$qa_classpath"' EXIT
mvn -q test-compile dependency:build-classpath -Dmdep.outputFile="$qa_classpath"
java -cp "target/test-classes:target/classes:$(cat "$qa_classpath")" panel.RedesignVisualSmoke "$qa_output"
