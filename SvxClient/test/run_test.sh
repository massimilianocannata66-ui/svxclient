#!/bin/bash
# Prova la parte di rete contro un finto reflector (serve Java e Python 3).
set -e
cd "$(dirname "$0")"
rm -rf out && mkdir out
javac -d out ../app/src/main/java/it/svxclient/core/*.java TestMain.java
PORT=25300
python3 mock_reflector.py $PORT mock.log &
MOCK=$!
trap "kill $MOCK 2>/dev/null" EXIT
sleep 1
java -cp out TestMain $PORT
