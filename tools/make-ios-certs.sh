#!/usr/bin/env bash
# Generate an Apple code-signing certificate on Linux. No Mac, no Keychain.
#
# Apple's docs assume you make the CSR in Keychain Access. Keychain Access just
# wraps a standard PKCS#10 request, so openssl does the same job.
#
# Run this in three passes:
#   ./make-ios-certs.sh csr      -> makes ios-dist.key and ios-dist.csr
#                                   (upload the .csr at developer.apple.com)
#   ./make-ios-certs.sh p12      -> combines your downloaded .cer with the key
#   ./make-ios-certs.sh secrets  -> prints the base64 blobs for GitHub secrets
#
# Fish shell note: this is a bash script, so run it as ./make-ios-certs.sh
# rather than sourcing it. The heredocs inside will not work under fish.

set -euo pipefail

NAME="${CERT_NAME:-Your Name}"
COUNTRY="${CERT_COUNTRY:-US}"
KEY=ios-dist.key
CSR=ios-dist.csr
CER=ios-dist.cer
P12=ios-dist.p12
WWDR=AppleWWDRCAG3.cer

die() { echo "error: $*" >&2; exit 1; }

case "${1:-}" in

csr)
  [ -f "$KEY" ] && die "$KEY already exists; delete it if you really want a new one"
  openssl genrsa -out "$KEY" 2048
  openssl req -new -key "$KEY" -out "$CSR" \
    -subj "/emailAddress=${CERT_EMAIL:-you@example.com}/CN=${NAME}/C=${COUNTRY}"
  echo
  echo "Created $KEY and $CSR"
  echo
  echo "Next, in a browser:"
  echo "  1. https://developer.apple.com/account/resources/certificates/add"
  echo "  2. Choose 'Apple Distribution'"
  echo "  3. Upload $CSR"
  echo "  4. Download the resulting .cer and save it here as $CER"
  echo "  5. Re-run: $0 p12"
  ;;

p12)
  [ -f "$KEY" ] || die "missing $KEY - run '$0 csr' first"
  [ -f "$CER" ] || die "missing $CER - download it from developer.apple.com"

  if [ ! -f "$WWDR" ]; then
    echo "Fetching Apple's intermediate certificate..."
    curl -fsSL -o "$WWDR" https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer
  fi

  openssl x509 -inform DER -in "$CER"  -out cert.pem
  openssl x509 -inform DER -in "$WWDR" -out wwdr.pem

  read -r -s -p "Choose a password for the .p12: " PW; echo
  [ -n "$PW" ] || die "the password cannot be empty; the runner needs it"

  # -legacy matters. OpenSSL 3 defaults to AES-256-CBC + PBKDF2 for PKCS#12,
  # which macOS Security.framework cannot read. Without it the CI run fails
  # with "MAC verification failed during PKCS12 import (wrong password?)"
  # and you waste an afternoon on a password that was never wrong.
  openssl pkcs12 -export -legacy \
    -inkey "$KEY" -in cert.pem -certfile wwdr.pem \
    -out "$P12" -passout "pass:$PW"

  rm -f cert.pem wwdr.pem
  echo
  echo "Created $P12"
  echo
  echo "Next, in a browser:"
  echo "  1. Register your bundle id at .../resources/identifiers/add"
  echo "  2. Create an App Store provisioning profile at .../resources/profiles/add"
  echo "     using the certificate you just made"
  echo "  3. Download it here as FrenTalk.mobileprovision"
  echo "  4. Re-run: $0 secrets"
  ;;

secrets)
  [ -f "$P12" ] || die "missing $P12 - run '$0 p12' first"
  PROFILE="${2:-FrenTalk.mobileprovision}"
  [ -f "$PROFILE" ] || die "missing $PROFILE"

  echo "Paste each of these into GitHub -> Settings -> Secrets and variables -> Actions."
  echo "Everything below is secret. Do not commit it."
  echo
  echo "=== BUILD_CERTIFICATE_BASE64 ==="
  base64 -w0 "$P12"; echo
  echo
  echo "=== PROVISIONING_PROFILE_BASE64 ==="
  base64 -w0 "$PROFILE"; echo
  echo
  echo "=== KEYCHAIN_PASSWORD (generated for you) ==="
  head -c 24 /dev/urandom | base64
  echo
  echo "Still to set by hand: P12_PASSWORD, APPLE_TEAM_ID, BUNDLE_ID, PROFILE_NAME"
  echo "The profile name must match exactly what you called it in the portal."
  ;;

*)
  echo "usage: $0 {csr|p12|secrets [profile.mobileprovision]}"
  exit 1
  ;;
esac
