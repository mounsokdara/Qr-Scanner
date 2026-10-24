#!/usr/bin/env bash
set -euo pipefail
IN="${1:?input apk}"; OUT="${2:?output apk}"
: "${KS_FILE:?}" "${KS_PASS:?}" "${KEY_ALIAS:?}" "${KEY_PASS:?}"
[ -f "$IN" ] || { echo "::error::input APK not found: $IN"; exit 1; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
APKSIGNER="${BT}apksigner"; ZIPALIGN="${BT}zipalign"
[ -x "$APKSIGNER" ] || { echo "::error::apksigner not found under $SDK/build-tools"; exit 1; }
echo "Using $APKSIGNER"

contents() { unzip -v "$1" | awk '$7 ~ /^[0-9a-f]{8}$/ && $8 !~ /^META-INF\// {print $7, $8}' | sort; }

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

if ! REPORT="$("$APKSIGNER" verify --verbose --print-certs --min-sdk-version 23 --v4-signature-file "$OUT.idsig" "$OUT" 2>&1)"; then
  echo "$REPORT"; echo "::error::apksigner verify FAILED"; exit 1
fi
grep -E "^Verifies|Verified using|Number of signers|certificate SHA-256" <<<"$REPORT" || true
for s in "v1 scheme (JAR signing)" "v2 scheme (APK Signature Scheme v2)" "v3 scheme (APK Signature Scheme v3)" "v4 scheme (APK Signature Scheme v4)"; do
  grep -qF "Verified using $s: true" <<<"$REPORT" || { echo "::error::missing signature: $s"; exit 1; }
done
unzip -l "$OUT" | grep -qE "META-INF/.+\.(RSA|EC|DSA)$" || { echo "::error::no v1 signature block in META-INF"; exit 1; }

GOT="$(grep -m1 'certificate SHA-256 digest' <<<"$REPORT" | awk '{print $NF}' | tr 'A-F' 'a-f')"
WANT="$(keytool -list -v -keystore "$KS_FILE" -storepass "$KS_PASS" -alias "$KEY_ALIAS" 2>/dev/null \
  | grep -m1 'SHA256:' | awk '{print $NF}' | tr -d ':' | tr 'A-F' 'a-f')"
[ -n "$GOT" ] && [ "$GOT" = "$WANT" ] || { echo "::error::certificate mismatch (apk=${GOT:-none} keystore=${WANT:-unreadable})"; exit 1; }

if ! diff <(contents "$IN") <(contents "$OUT") >/dev/null; then
  echo "::error::app contents changed while signing"; exit 1
fi
echo "contents unchanged: $(contents "$OUT" | wc -l) files identical to the input"

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
