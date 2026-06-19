#!/usr/bin/env bash
# Pobiera oficjalny BRouter JAR z GitHub releases i instaluje do lokalnego Maven repo.
# Idempotentny — sprawdza czy artefakt już jest w ~/.m2, pomija download jeśli tak.
#
# Uruchom raz lokalnie po klonowaniu repo:
#   bash route-service/scripts/setup-brouter-jar.sh
#
# CI: ten sam skrypt jako pre-step przed `mvn install`. ~/.m2 cache (np. setup-java
# action z `cache: maven`) sprawia, że kolejne buildy pomijają download.
#
# Update wersji: zmień BROUTER_VERSION poniżej + dependency w route-service/adapter/pom.xml,
# każdy dev i CI ponownie odpali skrypt.
set -euo pipefail

BROUTER_VERSION="${BROUTER_VERSION:-1.7.9}"
GROUP_ID="local.brouter"
ARTIFACT_ID="brouter"
ZIP_URL="https://github.com/abrensch/brouter/releases/download/v${BROUTER_VERSION}/brouter-${BROUTER_VERSION}.zip"
LIBS_DIR="$(cd "$(dirname "$0")/.." && pwd)/libs"
JAR_NAME="brouter-${BROUTER_VERSION}-all.jar"
M2_PATH="${HOME}/.m2/repository/local/brouter/brouter/${BROUTER_VERSION}/brouter-${BROUTER_VERSION}.jar"

if [[ -f "$M2_PATH" ]]; then
    echo "BRouter ${BROUTER_VERSION} już w ~/.m2 — pomijam install."
    exit 0
fi

mkdir -p "$LIBS_DIR"

if [[ ! -f "${LIBS_DIR}/${JAR_NAME}" ]]; then
    echo "Pobieram brouter-${BROUTER_VERSION}.zip z GitHub releases..."
    TMP_ZIP="$(mktemp --suffix=.zip)"
    curl -fL -o "$TMP_ZIP" "$ZIP_URL"

    # JAR siedzi w podkatalogu (brouter-X.Y.Z/brouter-X.Y.Z-all.jar). Glob `*${JAR_NAME}`
    # w unzip czasem nie matchuje przez `/` (różnice w implementacjach na MINGW/Linux),
    # więc wypakowujemy do tmp i szukamy po nazwie przez `find` — odporne na zmiany struktury ZIPa.
    echo "Wyciągam ${JAR_NAME} z ZIPa..."
    TMP_DIR="$(mktemp -d)"
    unzip -q -o "$TMP_ZIP" -d "$TMP_DIR"
    FOUND_JAR="$(find "$TMP_DIR" -name "${JAR_NAME}" -type f | head -n 1)"
    if [[ -z "$FOUND_JAR" ]]; then
        echo "ERROR: nie znaleziono ${JAR_NAME} w ZIPie. Pliki .jar znalezione w archiwum:"
        find "$TMP_DIR" -name "*.jar" -type f
        rm -rf "$TMP_DIR" "$TMP_ZIP"
        exit 1
    fi
    cp "$FOUND_JAR" "${LIBS_DIR}/${JAR_NAME}"
    rm -rf "$TMP_DIR" "$TMP_ZIP"
fi

echo "Instaluję do lokalnego Maven repo jako ${GROUP_ID}:${ARTIFACT_ID}:${BROUTER_VERSION}..."
mvn install:install-file \
    -Dfile="${LIBS_DIR}/${JAR_NAME}" \
    -DgroupId="${GROUP_ID}" \
    -DartifactId="${ARTIFACT_ID}" \
    -Dversion="${BROUTER_VERSION}" \
    -Dpackaging=jar \
    -DgeneratePom=true

echo "Gotowe — BRouter ${BROUTER_VERSION} dostępny jako ${GROUP_ID}:${ARTIFACT_ID}:${BROUTER_VERSION}"
