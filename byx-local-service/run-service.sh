#!/bin/zsh
# Inicia o serviço local em primeiro plano (Ctrl+C encerra). Usa o JDK e as dependências já presentes na máquina; nada é instalado
# globalmente, nada roda como root e nenhuma porta de rede é aberta (socket Unix privado em ~/.byx-local-service/run).
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$HOME/dev/tools/jdk-21.0.12.1+1/Contents/Home}"
export PATH="$JAVA_HOME/bin:$HOME/dev/tools/apache-maven-3.9.9/bin:$PATH"
M2="$HOME/.m2/repository/com/fasterxml/jackson/core"
CP="target/classes:$M2/jackson-databind/2.20.0/jackson-databind-2.20.0.jar:$M2/jackson-core/2.20.0/jackson-core-2.20.0.jar:$M2/jackson-annotations/2.20/jackson-annotations-2.20.jar"
[ -d target/classes ] || mvn -q -o -DskipTests compile
exec java -cp "$CP" byx.service.ServiceMain
