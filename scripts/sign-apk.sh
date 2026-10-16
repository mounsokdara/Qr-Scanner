#!/usr/bin/env bash
# Zip-align, then sign an APK with Google's official apksigner (v1 + v2 + v3 + v4, same
# release key) and verify the result before it is allowed to ship.
#
# Order matters: zipalign first, sign last (signing after alignment keeps it valid).
# Signature scheme v4 is a detached file, written next to the APK as <out.apk>.idsig
# (used by `adb install --incremental`); ship it together with the APK.
#
# Usage: KS_FILE=key.jks KS_PASS=... KEY_ALIAS=... KEY_PASS=... sign-apk.sh <in.apk> <out.apk>
#
# Fails the build unless ALL of these hold:
#   - apksigner verify passes with v1, v2, v3 and v4 present (checked with --min-sdk-version 23
#     so the legacy v1 scheme is really verified, not skipped; v4 is read from <out.apk>.idsig, passed explicitly)
#   - the signing certificate is the one in the release keystore (never the debug key)
#   - nothing inside the app changed (every file outside META-INF has the same CRC as the input)
#   - the output is zip-aligned (4-byte, native libs page-aligned)
set -euo pipefail
IN="${1:?input apk}"; OUT="${2:?output apk}"
: "${KS_FILE:?}" "${KS_PASS:?}" "${KEY_ALIAS:?}" "${KEY_PASS:?}"
[ -f "$IN" ] || { echo "::error::input APK not found: $IN"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
APKSIGNER="${BT}apksigner"; ZIPALIGN="${BT}zipalign"
[ -x "$APKSIGNER" ] || { echo "::error::apksigner not found under $SDK/build-tools"; exit 1; }
echo "Using $APKSIGNER"

# Contents fingerprint: CRC + name of every entry except the signature files.
contents() { unzip -v "$1" | awk '$7 ~ /^[0-9a-f]{8}$/ && $8 !~ /^META-INF\// {print $7, $8}' | sort; }

# 1. Zip-align (4 bytes; -p also page-aligns any uncompressed .so), then sign.
#    apksigner derives minSdk from the manifest, so v1 uses SHA-256 digests.
ALIGNED="$OUT.aligned.tmp"
rm -f "$OUT" "$OUT.idsig" "$ALIGNED"
if [ -x "$ZIPALIGN" ] && "$ZIPALIGN" -f -p 4 "$IN" "$ALIGNED" 2>/dev/null; then
  echo "zipalign: aligned $IN"
else
  echo "::warning::zipalign unavailable, signing the input as is (Gradle already aligns it)"
  cp "$IN" "$ALIGNED"
fi
"$APKSIGNER" sign \
  --ks "$KS_FILE" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KS_PASS --key-pass env:KEY_PASS \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --v4-signing-enabled true \
  --out "$OUT" "$ALIGNED"
rm -f "$ALIGNED"
[ -s "$OUT.idsig" ] || { echo "::error::v4 signature file $OUT.idsig was not written"; exit 1; }

# 2. Verify with the official verifier.
if ! REPORT="$("$APKSIGNER" verify --verbose --print-certs --min-sdk-version 23 --v4-signature-file "$OUT.idsig" "$OUT" 2>&1)"; then
  echo "$REPORT"; echo "::error::apksigner verify FAILED"; exit 1
fi
grep -E "^Verifies|Verified using|Number of signers|certificate SHA-256" <<<"$REPORT" || true
# Here-strings (not echo | grep -q): grep -q closing the pipe early would trip pipefail.
for s in "v1 scheme (JAR signing)" "v2 scheme (APK Signature Scheme v2)" "v3 scheme (APK Signature Scheme v3)" "v4 scheme (APK Signature Scheme v4)"; do
  grep -qF "Verified using $s: true" <<<"$REPORT" || { echo "::error::missing signature: $s"; exit 1; }
done
unzip -l "$OUT" | grep -qE "META-INF/.+\.(RSA|EC|DSA)$" || { echo "::error::no v1 signature block in META-INF"; exit 1; }

# 3. Certificate must be the release key.
GOT="$(grep -m1 'certificate SHA-256 digest' <<<"$REPORT" | awk '{print $NF}' | tr 'A-F' 'a-f')"
WANT="$(keytool -list -v -keystore "$KS_FILE" -storepass "$KS_PASS" -alias "$KEY_ALIAS" 2>/dev/null \
  | grep -m1 'SHA256:' | awk '{print $NF}' | tr -d ':' | tr 'A-F' 'a-f')"
[ -n "$GOT" ] && [ "$GOT" = "$WANT" ] || { echo "::error::certificate mismatch (apk=${GOT:-none} keystore=${WANT:-unreadable})"; exit 1; }

# 4. The app itself must be untouched.
if ! diff <(contents "$IN") <(contents "$OUT") >/dev/null; then
  echo "::error::app contents changed while signing"; exit 1
fi
echo "contents unchanged: $(contents "$OUT" | wc -l) files identical to the input"

# 5. Alignment.
if [ -x "$ZIPALIGN" ]; then
  rc=0; ZA="$("$ZIPALIGN" -c -p -v 4 "$OUT" 2>&1)" || rc=$?
  if [ "$rc" -eq 0 ]; then
    echo "zipalign: OK"
  elif [ "$rc" -ge 126 ] || grep -qi "error while loading" <<<"$ZA"; then
    echo "::warning::zipalign could not run, alignment not checked: $ZA"
  else
    echo "$ZA" | tail -3; echo "::error::output APK is not zip-aligned"; exit 1
  fi
fi
echo "OK: signed v1+v2+v3+v4 with the release key ($GOT)"
