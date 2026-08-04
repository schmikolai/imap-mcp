# OpenBao server config for the production stack (deploy/docker-compose.yml).
#
# storage is file-backed so the Transit key survives container/host
# restarts — OpenBao's "dev mode" is deliberately not used here since its
# in-memory storage would permanently destroy the ability to decrypt every
# already-stored IMAP password on every restart.
#
# tls_disable is safe here because this listener is only ever reached over
# the compose-internal network (never publish 8200 externally — see
# docker-compose.yml, same posture as postgres/redis).
storage "file" {
  # Must be /openbao/file, not an arbitrary path: docker-entrypoint.sh only
  # auto-chowns /openbao/config, /openbao/logs and /openbao/file for the
  # non-root "openbao" user it runs as — anything else stays root-owned and
  # the process can't write to it ("permission denied" on init).
  path = "/openbao/file"
}

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = "true"
}

# Avoids requiring the container to run with CAP_IPC_LOCK just to mlock
# memory against swapping — an acceptable trade-off for a single home-server
# instance, not a multi-tenant cloud deployment.
disable_mlock = true

api_addr = "http://openbao:8200"
