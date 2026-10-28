#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
. "$ROOT/build-common.sh"
exec mvn -f "$ROOT/pom.xml" clean install -DskipTests
