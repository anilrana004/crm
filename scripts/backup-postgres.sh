#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# SecureTravels CRM - PostgreSQL logical backup -> S3-compatible object store
#
# Runs on the Ubuntu VPS (and is exercised on the Windows dev box via Git Bash).
# Credentials and bucket are taken from the SAME environment variables the
# document-storage service already uses (STORAGE_*), so there is exactly one
# set of bucket credentials to rotate, not two.
#
# Failures are loud: every abnormal exit is non-zero and prints a reason.
# A backup that cannot be verified is treated as a failure, not a success.
#
# Usage:
#   ./backup-postgres.sh              # run a backup
#   ./backup-postgres.sh --self-test  # verify the SigV4 signer, touch nothing
#   ./backup-postgres.sh --dry-run    # do everything except upload/prune
# ---------------------------------------------------------------------------
set -Eeuo pipefail

readonly SCRIPT_NAME="${0##*/}"
SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"

# ---- defaults --------------------------------------------------------------
BACKUP_PREFIX="${BACKUP_PREFIX:-backups/postgres}"
BACKUP_SINK="${BACKUP_SINK:-s3}"                 # s3 | local
BACKUP_LOCAL_DIR="${BACKUP_LOCAL_DIR:-$SCRIPT_DIR/../.backups}"
BACKUP_RETAIN_DAYS="${BACKUP_RETAIN_DAYS:-14}"
BACKUP_MIN_BYTES="${BACKUP_MIN_BYTES:-1024}"     # 1 KiB floor
BACKUP_LOCK_DIR="${BACKUP_LOCK_DIR:-${TMPDIR:-/tmp}/securetravels-backup.lock}"
# Optional: when set, the dump is encrypted with age before upload.
BACKUP_AGE_PASSPHRASE="${BACKUP_AGE_PASSPHRASE:-}"

DRY_RUN=0
LOCK_HELD=0

# ---- logging ---------------------------------------------------------------
log()  { printf '%s [%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$SCRIPT_NAME" "$*"; }
warn() { log "WARNING: $*" >&2; }
die()  { log "ERROR: $*" >&2; exit 1; }

cleanup() {
  local rc=$?
  if [ "$LOCK_HELD" = "1" ]; then rmdir "$BACKUP_LOCK_DIR" 2>/dev/null || true; fi
  [ -n "${TMP_DIR:-}" ] && rm -rf "$TMP_DIR"
  exit "$rc"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# SigV4 (S3 REST) - self-contained, no AWS CLI / rclone / mc dependency.
# Only openssl + coreutils are required, both present on every Ubuntu image.
# ---------------------------------------------------------------------------
hex() { openssl dgst -sha256 -hex | sed 's/.*= //'; }
sha256_of_string() { printf '%s' "$1" | openssl dgst -sha256 -hex | sed 's/.*= //'; }
sha256_of_file()   { sha256sum "$1" | cut -d' ' -f1; }

# hmac_sha256 <hex-key> <data>  -> raw 32 bytes on stdout
hmac_sha256() {
  local hexkey="$1" data="$2" digest
  digest="$(printf '%s' "$data" \
    | openssl dgst -sha256 -mac HMAC -macopt "hexkey:${hexkey}" -hex | sed 's/.*= //')"
  printf '%s' "$digest" | xxd -r -p
}

# signing_key <secret> <datestamp> <region> <service> -> hex
signing_key() {
  local secret="$1" datestamp="$2" region="$3" service="$4" k
  k="$(printf 'AWS4%s' "$secret" | xxd -p | tr -d '\n')"   # "AWS4"+secret as hex
  k="$(hmac_sha256 "$k" "$datestamp"        | xxd -p | tr -d '\n')"
  k="$(hmac_sha256 "$k" "$region"           | xxd -p | tr -d '\n')"
  k="$(hmac_sha256 "$k" "$service"          | xxd -p | tr -d '\n')"
  k="$(hmac_sha256 "$k" "aws4_request"      | xxd -p | tr -d '\n')"
  printf '%s' "$k"
}

# sign_request <method> <canonical-uri> <canonical-query>
#               <canonical-headers-block> <signed-headers>
#               <payload-hash> <amz-date> <datestamp> <region> <secret>
# -> hex signature
sign_request() {
  local method="$1" uri="$2" query="$3" canon_headers="$4" signed="$5"
  local payload_hash="$6" amzdate="$7" datestamp="$8" region="$9" secret="${10}"
  local scope="${datestamp}/${region}/s3/aws4_request"
  local canonical_request string_to_sign key sig

  # CanonicalHeaders must itself end with a newline; the CanonicalRequest
  # format then adds another, producing the mandatory blank line before
  # SignedHeaders. Omitting it yields a plausible-but-wrong signature that S3
  # rejects with SignatureDoesNotMatch.
  [ -n "$canon_headers" ] && [ "${canon_headers: -1}" != $'\n' ] \
    && canon_headers="${canon_headers}"$'\n'

  canonical_request="${method}
${uri}
${query}
${canon_headers}
${signed}
${payload_hash}"

  string_to_sign="AWS4-HMAC-SHA256
${amzdate}
${scope}
$(sha256_of_string "$canonical_request")"

  key="$(signing_key "$secret" "$datestamp" "$region" s3)"
  sig="$(hmac_sha256 "$key" "$string_to_sign" | xxd -p | tr -d '\n')"
  if [ "${BACKUP_DEBUG:-0}" = "1" ]; then
    {
      printf '=== CANONICAL REQUEST ===\n%s\n' "$canonical_request"
      printf '=== STRING TO SIGN ===\n%s\n' "$string_to_sign"
      printf '=== SIGNING KEY ===\n%s\n' "$key"
      printf '=== SIGNATURE ===\n%s\n' "$sig"
    } >&2
  fi
  printf '%s' "$sig"
}

# RFC 3986 encode; '/' preserved when encode_slash=0
uri_encode() {
  local value="$1" encode_slash="${2:-1}" out="" i c
  for (( i = 0; i < ${#value}; i++ )); do
    c="${value:i:1}"
    case "$c" in
      [A-Za-z0-9.~_-]) out+="$c" ;;
      /) if [ "$encode_slash" = "0" ]; then out+="/"; else out+="%2F"; fi ;;
      *)  out+="$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}

# ---------------------------------------------------------------------------
# --self-test: prove the signer against the published AWS SigV4 S3 example.
# A silently-wrong signer would produce 403 SignatureDoesNotMatch on every
# upload, i.e. backups that appear to run and store nothing.
# ---------------------------------------------------------------------------
self_test() {
  log "running SigV4 self-test (AWS published example: GET Object)"

  # AWS SigV4 docs, "Example: GET Object"
  local got want
  want="f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
  got="$(sign_request \
      "GET" \
      "/test.txt" \
      "" \
      "host:examplebucket.s3.amazonaws.com
range:bytes=0-9
x-amz-content-sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
x-amz-date:20130524T000000Z" \
      "host;range;x-amz-content-sha256;x-amz-date" \
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" \
      "20130524T000000Z" \
      "20130524" \
      "us-east-1" \
      "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")"

  if [ "$got" != "$want" ]; then
    die "SigV4 self-test FAILED
  expected: $want
  actual:   $got"
  fi
  log "SigV4 self-test PASSED (signature matches AWS reference vector)"

  # uri_encode must leave separators alone but escape everything else.
  [ "$(uri_encode "a b/c")" = "a%20b%2Fc" ] || die "uri_encode(query mode) wrong"
  [ "$(uri_encode "a b/c" 0)" = "a%20b/c" ] || die "uri_encode(path mode) wrong"
  log "uri_encode self-test PASSED"
  log "self-test complete; no backup was taken and nothing was uploaded"
}

# ---------------------------------------------------------------------------
# S3 upload (path-style, header-authenticated SigV4 PUT)
# ---------------------------------------------------------------------------
s3_put() {
  local file="$1" object_key="$2"

  : "${STORAGE_ENDPOINT:?STORAGE_ENDPOINT is required (e.g. https://s3.example.com)}"
  : "${STORAGE_REGION:?STORAGE_REGION is required}"
  : "${STORAGE_BUCKET:?STORAGE_BUCKET is required}"
  : "${STORAGE_ACCESS_KEY:?STORAGE_ACCESS_KEY is required}"
  : "${STORAGE_SECRET_KEY:?STORAGE_SECRET_KEY is required}"

  local endpoint="${STORAGE_ENDPOINT%/}"
  local amzdate datestamp payload_hash host uri canon_headers signed sig url

  amzdate="$(date -u +%Y%m%dT%H%M%SZ)"
  datestamp="${amzdate:0:8}"
  payload_hash="$(sha256_of_file "$file")"
  host="$(printf '%s' "$endpoint" | sed -E 's#^https?://##; s#/$##')"

  # path-style, matching the document-storage signer (SignedUploadUrlService)
  uri="/$(uri_encode "$STORAGE_BUCKET" 0)/$(uri_encode "$object_key" 0)"

  canon_headers="host:${host}
x-amz-content-sha256:${payload_hash}
x-amz-date:${amzdate}"
  signed="host;x-amz-content-sha256;x-amz-date"

  sig="$(sign_request "PUT" "$uri" "" "$canon_headers" "$signed" \
        "$payload_hash" "$amzdate" "$datestamp" \
        "$STORAGE_REGION" "$STORAGE_SECRET_KEY")"

  # The credential MUST travel with the request. Signing without sending the
  # Authorization header yields an anonymous PUT, which the store rejects with a
  # bare "AccessDenied" - not a signature error, so it is easy to misdiagnose.
  local authorization
  authorization="AWS4-HMAC-SHA256 Credential=${STORAGE_ACCESS_KEY}/${datestamp}/${STORAGE_REGION}/s3/aws4_request, SignedHeaders=${signed}, Signature=${sig}"

  url="${endpoint}${uri}"

  log "PUT ${url} (${payload_hash:0:12}...)"
  if [ "$DRY_RUN" = "1" ]; then log "dry-run: skipping upload"; return 0; fi

  # NOTE: Content-Type is actively suppressed here. Every header sent must appear
  # in SignedHeaders (host;x-amz-content-sha256;x-amz-date) or the store rejects
  # the request. curl's --data-binary injects
  # "Content-Type: application/x-www-form-urlencoded" on its own, so the header
  # must be explicitly blanked ("Content-Type:") rather than merely omitted.
  # Expect: is suppressed for the same reason (curl adds it for larger bodies).
  local code
  code="$(curl -sS -o /tmp/securetravels-upload-body -w '%{http_code}' \
      -X PUT \
      -H "Authorization: ${authorization}" \
      -H "x-amz-date: ${amzdate}" \
      -H "x-amz-content-sha256: ${payload_hash}" \
      -H "Content-Type:" \
      -H "Expect:" \
      --data-binary "@${file}" \
      "${url}")" || die "upload failed (curl error) for ${url}"
  if [ "$code" != "200" ] && [ "$code" != "201" ] && [ "$code" != "204" ]; then
    warn "store response: $(tr -d '\n' < /tmp/securetravels-upload-body | head -c 400)"
  fi

  case "$code" in
    200|201|204) log "upload OK (HTTP ${code})" ;;
    403) die "upload REJECTED 403 SignatureDoesNotMatch - check STORAGE_REGION / STORAGE_SECRET_KEY (run --self-test)" ;;
    404) die "upload REJECTED 404 NoSuchBucket - check STORAGE_BUCKET" ;;
    *)   die "upload REJECTED HTTP ${code}" ;;
  esac
}

# ---------------------------------------------------------------------------
prune_local() {
  [ "$DRY_RUN" = "1" ] && { log "dry-run: skipping prune"; return 0; }
  [ -d "$BACKUP_LOCAL_DIR" ] || return 0
  local removed
  removed="$(find "$BACKUP_LOCAL_DIR" -maxdepth 1 -type f -name '*.dump*' -mtime "+${BACKUP_RETAIN_DAYS}" -print -delete 2>/dev/null | wc -l | tr -d ' ')"
  log "pruned ${removed} local dump(s) older than ${BACKUP_RETAIN_DAYS}d"
}

# ---------------------------------------------------------------------------
main() {
  case "${1:-}" in
    --self-test) self_test; return 0 ;;
    --dry-run)   DRY_RUN=1 ;;
    "")          ;;
    *)           die "unknown argument: $1 (use --self-test or --dry-run)" ;;
  esac

  # Single-instance guard: mkdir is atomic on every POSIX filesystem.
  if ! mkdir "$BACKUP_LOCK_DIR" 2>/dev/null; then
    die "another backup is already running (lock: ${BACKUP_LOCK_DIR})"
  fi
  LOCK_HELD=1

  # DB connection: accept the app's own names, fall back to libpq names.
  local dbhost="${PGHOST:-127.0.0.1}"
  local dbport="${PGPORT:-5432}"
  local dbname="${PGDATABASE:-${DB_NAME:-securetravels_crm}}"
  local dbuser="${PGUSER:-${DB_USER:-postgres}}"
  export PGPASSWORD="${PGPASSWORD:-${DB_PASSWORD:-}}"

  command -v pg_dump >/dev/null 2>&1 || die "pg_dump not on PATH"
  command -v pg_restore >/dev/null 2>&1 || die "pg_restore not on PATH"

  TMP_DIR="$(mktemp -d)"
  local stamp out
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  out="${TMP_DIR}/${dbname}-${stamp}.dump"

  log "dumping ${dbname} from ${dbhost}:${dbport} as ${dbuser}"
  local t0 t1
  t0=$(date +%s)
  # -Fc custom format: compressed, and restorable with pg_restore. Do NOT gzip
  # again - pg_dump -Fc is already compressed and double-gzipping only adds a
  # pointless CPU cost and a needless failure mode.
  if ! pg_dump -Fc --no-owner --no-privileges \
        -h "$dbhost" -p "$dbport" -U "$dbuser" -d "$dbname" -f "$out"; then
    die "pg_dump FAILED for ${dbname}"
  fi
  t1=$(date +%s)
  log "dump written in $((t1 - t0))s"

  [ -s "$out" ] || die "dump is empty (0 bytes) - treating as failure"
  local size
  size="$(wc -c < "$out" | tr -d ' ')"
  [ "$size" -ge "$BACKUP_MIN_BYTES" ] \
    || die "dump is only ${size} bytes (< BACKUP_MIN_BYTES=${BACKUP_MIN_BYTES}) - refusing to upload a truncated dump"
  log "dump size: ${size} bytes"

  # Integrity check: reading the archive TOC proves it is a valid, complete
  # pg_dump -Fc file. A truncated or half-written dump fails here.
  if ! pg_restore --list "$out" >/dev/null 2>"${TMP_DIR}/toc.err"; then
    warn "pg_restore --list failed: $(head -1 "${TMP_DIR}/toc.err")"
    die "dump failed integrity check - NOT uploading"
  fi
  local toc_entries
  toc_entries="$(pg_restore --list "$out" 2>/dev/null | grep -c '^[0-9]' || true)"
  log "integrity check OK (${toc_entries} archive entries)"

  # Checksum, kept next to the dump for verification. Written in standard
  # `sha256sum` format (hash + basename) so `sha256sum -c` works and the file
  # stays valid after the dump is moved to another directory.
  write_checksum() { ( cd "$(dirname "$1")" && sha256sum "$(basename "$1")" ) > "$1.sha256"; }
  write_checksum "$out"

  if [ -n "$BACKUP_AGE_PASSPHRASE" ]; then
    command -v age >/dev/null 2>&1 \
      || die "BACKUP_AGE_PASSPHRASE is set but 'age' is not installed"
    log "encrypting with age"
    age --passphrase "$BACKUP_AGE_PASSPHRASE" \
        -o "${out}.age" -r "${out}" \
      || die "age encryption FAILED"
    out="${out}.age"
    write_checksum "$out"
  fi

  local object_key="${BACKUP_PREFIX}/${dbname}-${stamp}.dump"
  [ "$DRY_RUN" = "1" ] && log "dry-run: would upload to ${BACKUP_PREFIX}/${dbname}-*.dump"

  case "$BACKUP_SINK" in
    s3)
      s3_put "$out" "$object_key"
      ;;
    local)
      if [ "$DRY_RUN" = "1" ]; then
        log "dry-run: would copy to ${BACKUP_LOCAL_DIR} (local sink)"
      else
        mkdir -p "$BACKUP_LOCAL_DIR"
        cp "$out" "${BACKUP_LOCAL_DIR}/"
        cp "${out}.sha256" "${BACKUP_LOCAL_DIR}/"
        log "copied to ${BACKUP_LOCAL_DIR} (local sink)"
      fi
      ;;
    *) die "BACKUP_SINK must be 's3' or 'local', got '${BACKUP_SINK}'" ;;
  esac

  prune_local
  log "backup COMPLETE (${object_key})"
}

main "$@"
