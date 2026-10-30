#!/bin/sh
# Compila il client DLNA (puro Java, senza Android) e lo prova contro il finto NAS.
# Richiede: java 11+ e python3.
set -e
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
javac -d "$OUT" app/src/main/java/it/nascasa/tv/dlna/*.java tests/DlnaSelfTest.java
python3 tests/mock_dlna.py &
MOCK=$!
trap 'kill $MOCK 2>/dev/null' EXIT
sleep 1
java -cp "$OUT" it.nascasa.tv.dlna.DlnaSelfTest
