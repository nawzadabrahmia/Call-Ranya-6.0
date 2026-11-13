#!/usr/bin/env bash
# Builds the relay emulator: runs the REAL Call Ranya relay code (Relay.kt, Crypto.kt, Messenger.kt, …) on a desktop JVM,
# one process per "phone", talking over TCP on 127.0.0.x. Needs JDK 17+, curl, unzip, python3.
set -e
cd "$(dirname "$0")"; T=tools; mkdir -p $T
[ -f $T/android.jar ] || curl -sSL -o $T/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-34/android.jar
[ -d $T/kotlinc ] || { curl -sSL -o $T/kc.zip https://github.com/JetBrains/kotlin/releases/download/v2.0.20/kotlin-compiler-2.0.20.zip && unzip -q $T/kc.zip -d $T; }
[ -f $T/sqlite-jdbc.jar ] || curl -sSL -o $T/sqlite-jdbc.jar https://github.com/xerial/sqlite-jdbc/releases/download/3.41.2.2/sqlite-jdbc-3.41.2.2.jar
if [ ! -d $T/jsonc ]; then mkdir -p $T/jsrc $T/jsonc
  for f in JSONObject JSONArray JSONTokener JSONException JSONString JSONPropertyIgnore JSONPropertyName JSONPointer JSONPointerException JSONParserConfiguration ParserConfiguration JSONWriter; do
    curl -sSL -o $T/jsrc/$f.java https://raw.githubusercontent.com/stleary/JSON-java/20240303/src/main/java/org/json/$f.java; done
  javac -nowarn -d $T/jsonc $T/jsrc/*.java; fi
SRC=../../app/src/main/java/edu/uor/lancall
K="$T/kotlinc/bin/kotlinc -nowarn -jvm-target 17"
javac -nowarn -d shimc -cp $T/sqlite-jdbc.jar $(find shim -name "*.java")
$K -cp $T/android.jar:$T/kotlinc/lib/kotlinx-coroutines-core-jvm.jar $SRC/Crypto.kt $SRC/Store.kt $SRC/Relay.kt $SRC/Messenger.kt \
   $SRC/Discovery.kt $SRC/Net.kt $SRC/Safety.kt $SRC/L.kt stubs/*.kt -d appc
CP=shimc:$T/jsonc:appc:$T/android.jar:$T/kotlinc/lib/kotlinx-coroutines-core-jvm.jar
$K -cp $CP Node.kt MemPrefs.kt -d nodec
$K -cp $CP HandshakeTest.kt MemPrefs.kt -d testc
echo "Built. Run: python3 run.py   (3-phone scenario)   or   java -cp testc:$CP:$T/kotlinc/lib/kotlin-stdlib.jar HandshakeTestKt"
