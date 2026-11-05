#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TEST_SECRETS_DIR:?Set an external temporary directory; never keep private keys in source}"
mkdir -p "$TEST_SECRETS_DIR"
case "$(realpath "$TEST_SECRETS_DIR")/" in "$(pwd)/"*) echo 'Keys must be outside the source tree' >&2; exit 1;; esac
chmod 700 "$TEST_SECRETS_DIR"
umask 077
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj '/CN=Local Android Fixture CA' -keyout "$TEST_SECRETS_DIR/ca.key" -out "$TEST_SECRETS_DIR/ca.pem" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -subj '/CN=localhost' -keyout "$TEST_SECRETS_DIR/server.key" -out "$TEST_SECRETS_DIR/server.csr" >/dev/null 2>&1
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2\nextendedKeyUsage=serverAuth\n' > "$TEST_SECRETS_DIR/extensions.cnf"
openssl x509 -req -days 2 -in "$TEST_SECRETS_DIR/server.csr" -CA "$TEST_SECRETS_DIR/ca.pem" -CAkey "$TEST_SECRETS_DIR/ca.key" -CAcreateserial -extfile "$TEST_SECRETS_DIR/extensions.cnf" -out "$TEST_SECRETS_DIR/server.pem" >/dev/null 2>&1
cp "$TEST_SECRETS_DIR/ca.pem" app/src/debug/res/raw/hardening_test_ca.pem
