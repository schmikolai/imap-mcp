#!/usr/bin/env bash
# Generates/updates dist/.env for docker-compose.prod.yml.
#
# Idempotent by default: any key already present and non-empty in an
# existing .env is left untouched. Only missing or empty keys are
# (re)generated/filled in, then merged back into the file. This makes it
# safe to rerun against an already-deployed stack -- DB_OWNER_PASSWORD,
# DB_APP_PASSWORD, and DB_GRAFANA_PASSWORD are baked into their Postgres
# roles at first migration, so silently regenerating them would lock the
# app (or Grafana) out of the database.
#
# Pass --force to regenerate the secret keys (DB_OWNER_PASSWORD,
# DB_APP_PASSWORD, DB_GRAFANA_PASSWORD, OAUTH_SEED_CLIENT_SECRET) even if
# already set -- only do this for a from-scratch deploy, or after also
# updating the corresponding Postgres roles' passwords (or wiping the
# postgres volume).
#
# Interactive by default: values that have no safe default (IMAGE,
# OPENBAO_TOKEN, OAUTH_ISSUER_URI, OAUTH_SEED_CLIENT_ID,
# OAUTH_SEED_CLIENT_NAME, OAUTH_SEED_CLIENT_REDIRECT_URIS) and aren't
# already set in .env are prompted for. Press enter at a prompt to leave
# that value empty. Pass -n/--non-interactive (e.g. for CI/scripted runs)
# to skip prompting and just leave them empty instead.
set -euo pipefail

DIST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$DIST_DIR/.env"

FORCE=0
INTERACTIVE=1
for arg in "$@"; do
    case "$arg" in
        --force) FORCE=1 ;;
        -n|--non-interactive) INTERACTIVE=0 ;;
        -i|--interactive) INTERACTIVE=1 ;;
        -h|--help)
            echo "Usage: $0 [--force] [-n|--non-interactive]"
            exit 0
            ;;
        *)
            echo "error: unknown argument '$arg'" >&2
            exit 1
            ;;
    esac
done

if ! command -v openssl >/dev/null 2>&1; then
    echo "error: openssl is required to generate secrets." >&2
    exit 1
fi

rand_hex() { openssl rand -hex "$1"; }

# --- Load existing values, if any, so they survive the regeneration. ---
declare -A existing
if [[ -f "$ENV_FILE" ]]; then
    while IFS='=' read -r key value; do
        existing["$key"]="$value"
    done < <(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$ENV_FILE")
fi

# Keys whose values are security-sensitive random secrets. Regenerated
# only when missing/empty, or always when --force is passed.
SECRET_KEYS=(DB_OWNER_PASSWORD DB_APP_PASSWORD DB_GRAFANA_PASSWORD OAUTH_SEED_CLIENT_SECRET)

# Keys with a sane structural default -- filled in only if missing/empty,
# never overwritten (these aren't secrets, just first-run scaffolding).
declare -A STATIC_DEFAULTS=(
    [IMAGE_TAG]="latest"
    [PORT]="8080"
    [POSTGRES_DB]="imapmcp"
    [DB_URL]="jdbc:postgresql://postgres:5432/imapmcp"
    [DB_OWNER_USER]="imapmcp"
    [DB_APP_USER]="imapmcp_app"
    [DB_GRAFANA_USER]="grafana_ro"
    [REDIS_HOST]="redis"
    [REDIS_PORT]="6379"
    [OPENBAO_ADDR]="http://openbao:8200"
    [OPENBAO_TRANSIT_KEY]="imap-mcp-dek"
)

# Keys with no safe default -- left empty unless already set, or unless
# -i is passed and the user provides a value at the prompt.
declare -A MANUAL_PROMPTS=(
    [IMAGE]="URI of the container image to run"
    [OPENBAO_TOKEN]="OpenBao transit token (from the OpenBao setup runbook in README.md)"
    [OAUTH_ISSUER_URI]="This server's real, fixed public URL (e.g. https://mcp.example.com)"
    [OAUTH_SEED_CLIENT_ID]="Seed OAuth client id (leave blank to skip seeding one)"
    [OAUTH_SEED_CLIENT_NAME]="Seed OAuth client display name"
    [OAUTH_SEED_CLIENT_REDIRECT_URIS]="Seed OAuth client redirect URI(s), comma-separated"
)

GENERATED_KEYS=()
STILL_EMPTY_KEYS=()

resolve_secret() {
    local key="$1"
    local cur="${existing[$key]:-}"
    if [[ -n "$cur" && "$FORCE" -ne 1 ]]; then
        printf '%s' "$cur"
    else
        GENERATED_KEYS+=("$key")
        rand_hex 32
    fi
}

resolve_static() {
    local key="$1"
    local cur="${existing[$key]:-}"
    if [[ -n "$cur" ]]; then
        printf '%s' "$cur"
    else
        printf '%s' "${STATIC_DEFAULTS[$key]}"
    fi
}

resolve_manual() {
    local key="$1"
    local cur="${existing[$key]:-}"
    if [[ -n "$cur" ]]; then
        printf '%s' "$cur"
        return
    fi
    if [[ "$INTERACTIVE" -eq 1 ]]; then
        local prompt="${MANUAL_PROMPTS[$key]}"
        local input=""
        read -r -p "$key -- $prompt: " input </dev/tty || input=""
        if [[ -n "$input" ]]; then
            printf '%s' "$input"
            return
        fi
    fi
    STILL_EMPTY_KEYS+=("$key")
    printf ''
}

IMAGE=$(resolve_manual IMAGE)
IMAGE_TAG=$(resolve_static IMAGE_TAG)
PORT=$(resolve_static PORT)
POSTGRES_DB=$(resolve_static POSTGRES_DB)
DB_URL=$(resolve_static DB_URL)
DB_OWNER_USER=$(resolve_static DB_OWNER_USER)
DB_OWNER_PASSWORD=$(resolve_secret DB_OWNER_PASSWORD)
DB_APP_USER=$(resolve_static DB_APP_USER)
DB_APP_PASSWORD=$(resolve_secret DB_APP_PASSWORD)
DB_GRAFANA_USER=$(resolve_static DB_GRAFANA_USER)
DB_GRAFANA_PASSWORD=$(resolve_secret DB_GRAFANA_PASSWORD)
REDIS_HOST=$(resolve_static REDIS_HOST)
REDIS_PORT=$(resolve_static REDIS_PORT)
OPENBAO_ADDR=$(resolve_static OPENBAO_ADDR)
OPENBAO_TOKEN=$(resolve_manual OPENBAO_TOKEN)
OPENBAO_TRANSIT_KEY=$(resolve_static OPENBAO_TRANSIT_KEY)
OAUTH_ISSUER_URI=$(resolve_manual OAUTH_ISSUER_URI)
OAUTH_SEED_CLIENT_ID=$(resolve_manual OAUTH_SEED_CLIENT_ID)
OAUTH_SEED_CLIENT_SECRET=$(resolve_secret OAUTH_SEED_CLIENT_SECRET)
OAUTH_SEED_CLIENT_NAME=$(resolve_manual OAUTH_SEED_CLIENT_NAME)
OAUTH_SEED_CLIENT_REDIRECT_URIS=$(resolve_manual OAUTH_SEED_CLIENT_REDIRECT_URIS)

KNOWN_KEYS=(
    IMAGE IMAGE_TAG PORT POSTGRES_DB DB_URL DB_OWNER_USER DB_OWNER_PASSWORD
    DB_APP_USER DB_APP_PASSWORD DB_GRAFANA_USER DB_GRAFANA_PASSWORD
    REDIS_HOST REDIS_PORT OPENBAO_ADDR OPENBAO_TOKEN OPENBAO_TRANSIT_KEY
    OAUTH_ISSUER_URI OAUTH_SEED_CLIENT_ID OAUTH_SEED_CLIENT_SECRET
    OAUTH_SEED_CLIENT_NAME OAUTH_SEED_CLIENT_REDIRECT_URIS
)

{
    echo "# Generated/updated by generate-env.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)."
    echo "# Contains live secrets -- do not commit this file."
    echo
    echo "# --- Container image (must match what .github/workflows/docker-publish.yml pushed) ---"
    echo "IMAGE=$IMAGE"
    echo "IMAGE_TAG=$IMAGE_TAG"
    echo
    echo "# --- App ---"
    echo "PORT=$PORT"
    echo
    echo "# --- Database ---"
    echo "# Compose-internal hostname/port -- do not change unless you also edit"
    echo "# docker-compose.prod.yml's service name."
    echo "POSTGRES_DB=$POSTGRES_DB"
    echo "DB_URL=$DB_URL"
    echo "# Schema-owning role Flyway runs migrations as."
    echo "DB_OWNER_USER=$DB_OWNER_USER"
    echo "DB_OWNER_PASSWORD=$DB_OWNER_PASSWORD"
    echo "# Least-privilege role the app connects as at runtime (created by the"
    echo "# V4 migration the first time this stack comes up)."
    echo "DB_APP_USER=$DB_APP_USER"
    echo "DB_APP_PASSWORD=$DB_APP_PASSWORD"
    echo "# Read-only role, SELECT on audit_log only, for Grafana's PostgreSQL data"
    echo "# source (created by the V5 migration the first time this stack comes up)."
    echo "DB_GRAFANA_USER=$DB_GRAFANA_USER"
    echo "DB_GRAFANA_PASSWORD=$DB_GRAFANA_PASSWORD"
    echo
    echo "# --- Redis ---"
    echo "REDIS_HOST=$REDIS_HOST"
    echo "REDIS_PORT=$REDIS_PORT"
    echo
    echo "# --- Credential encryption (self-hosted OpenBao Transit engine) ---"
    echo "# OPENBAO_TOKEN cannot be auto-generated here -- it's minted only after you've"
    echo "# run OpenBao's one-time init/unseal/transit-setup runbook (see README.md),"
    echo "# since it must be a least-privilege token scoped to the transit key below,"
    echo "# not the initial root token."
    echo "OPENBAO_ADDR=$OPENBAO_ADDR"
    echo "OPENBAO_TOKEN=$OPENBAO_TOKEN"
    echo "OPENBAO_TRANSIT_KEY=$OPENBAO_TRANSIT_KEY"
    echo
    echo "# --- OAuth ---"
    echo "# Must be this server's real, fixed public URL in production -- cannot be"
    echo "# auto-generated. See the OAuth token flow note in CLAUDE.md."
    echo "OAUTH_ISSUER_URI=$OAUTH_ISSUER_URI"
    echo "# Optional statically-vetted seed client -- leave OAUTH_SEED_CLIENT_ID blank"
    echo "# to skip seeding one. A secret is pre-generated below either way so it's"
    echo "# ready to use the moment you set a client id."
    echo "OAUTH_SEED_CLIENT_ID=$OAUTH_SEED_CLIENT_ID"
    echo "OAUTH_SEED_CLIENT_SECRET=$OAUTH_SEED_CLIENT_SECRET"
    echo "OAUTH_SEED_CLIENT_NAME=$OAUTH_SEED_CLIENT_NAME"
    echo "OAUTH_SEED_CLIENT_REDIRECT_URIS=$OAUTH_SEED_CLIENT_REDIRECT_URIS"

    # Preserve any keys that existed in the old .env but aren't part of
    # the standard template (e.g. local overrides someone added by hand).
    extra_found=0
    for key in "${!existing[@]}"; do
        known=0
        for k in "${KNOWN_KEYS[@]}"; do
            [[ "$key" == "$k" ]] && known=1 && break
        done
        if [[ "$known" -eq 0 ]]; then
            if [[ "$extra_found" -eq 0 ]]; then
                echo
                echo "# --- Preserved from previous .env (not part of the standard template) ---"
                extra_found=1
            fi
            echo "$key=${existing[$key]}"
        fi
    done
} >"$ENV_FILE"

chmod 600 "$ENV_FILE"

echo "Wrote $ENV_FILE (mode 600)."
if [[ "${#GENERATED_KEYS[@]}" -gt 0 ]]; then
    echo "Newly generated secrets: ${GENERATED_KEYS[*]}"
fi
if [[ "${#STILL_EMPTY_KEYS[@]}" -gt 0 ]]; then
    echo "Still needs manual values before 'docker compose -f docker-compose.prod.yml up -d':"
    for key in "${STILL_EMPTY_KEYS[@]}"; do
        echo "  $key -- ${MANUAL_PROMPTS[$key]}"
    done
    if [[ "$INTERACTIVE" -eq 0 ]]; then
        echo "Rerun without -n/--non-interactive to be prompted for these."
    fi
fi
