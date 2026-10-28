#!/bin/sh
# Shared Java setup for the POSIX build scripts. Source this file rather than executing it.
if [ -z "${JAVA_HOME:-}" ] && [ "$(uname -s)" = Darwin ]; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export JAVA_HOME
fi
if [ -n "${JAVA_HOME:-}" ]; then
    PATH="$JAVA_HOME/bin:$PATH"
    export PATH
fi
if ! command -v java >/dev/null 2>&1 || ! command -v mvn >/dev/null 2>&1; then
    printf '%s\n' 'Java and Maven are required. Set JAVA_HOME to your JDK 17 installation and install Maven.' >&2
    exit 1
fi
