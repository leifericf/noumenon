# Deploying Noumenon as a Shared Service

Run Noumenon as a centralized knowledge graph for your team or organization.

## Quick Start

```bash
# 1. Clone and configure
git clone https://github.com/leifericf/noumenon.git
cd noumenon
cp .env.example .env
# Edit .env — set NOUMENON_TOKEN to a random secret

# 2. Start
docker compose up -d

# 3. Register a repository
curl -X POST http://localhost:7891/api/repos \
  -H "Authorization: Bearer $NOUMENON_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/your-org/your-repo.git"}'

# 4. Create reader tokens for your team
curl -X POST http://localhost:7891/api/tokens \
  -H "Authorization: Bearer $NOUMENON_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"role": "reader", "label": "alice"}'
```

## User Setup

Each user installs the CLI and connects:

```bash
brew install leifericf/noumenon/noumenon
noum connect https://noumenon.example.com --token <their-token>
noum ask your-repo "What are the main components?"
```

MCP tools in Claude Code work automatically — no additional config needed.

## Configuration

All settings are env vars. No config files needed.

| Variable | Default | Description |
|----------|---------|-------------|
| `NOUMENON_TOKEN` | — | **Required.** Admin bootstrap token |
| `NOUMENON_BIND` | `127.0.0.1` | Bind address (`0.0.0.0` for Docker) |
| `NOUMENON_PORT` | `7891` | Listen port |
| `NOUMENON_DB_DIR` | `/data` | Datomic storage directory |
| `NOUMENON_LLM_PROVIDER` | — | LLM provider (`glm`, `claude-api`) |
| `NOUMENON_LLM_MODEL` | — | Model alias (`sonnet`, `haiku`, `opus`) |
| `NOUMENON_READ_ONLY` | `false` | Reject all mutations (maintenance mode) |
| `NOUMENON_MAX_ASK_SESSIONS` | `50` | Max concurrent ask sessions |
| `NOUMENON_MAX_LLM_CONCURRENCY` | `10` | Max concurrent LLM API calls |
| `NOUMENON_POLL_INTERVAL` | `5` | Auto-refresh interval (minutes), 0 = off |
| `NOUMENON_LOG_FORMAT` | `text` | `text` or `json` |
| `NOUMENON_WEBHOOK_SECRET` | — | HMAC secret for GitHub/GitLab webhooks |

All `_TOKEN` and `_SECRET` vars support a `_FILE` suffix for Docker secrets:
`NOUMENON_TOKEN_FILE=/run/secrets/admin_token`

## Roles

| Role | Can do |
|------|--------|
| **admin** | Everything: import, analyze, manage tokens, delete databases |
| **reader** | Query, ask, status, benchmarks — read-only access |

The `NOUMENON_TOKEN` env var is always admin. Create reader tokens via the API.

## Reverse Proxy (TLS)

Noumenon does not terminate TLS. Put it behind Nginx or Caddy.

### Nginx

```nginx
server {
    listen 443 ssl;
    server_name noumenon.example.com;

    ssl_certificate /etc/letsencrypt/live/noumenon.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/noumenon.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:7891;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;

        # SSE support (required for progress streaming)
        proxy_buffering off;
        proxy_read_timeout 600s;
        proxy_set_header X-Accel-Buffering no;
    }
}
```

### Caddy

```
noumenon.example.com {
    reverse_proxy localhost:7891
}
```

Caddy handles TLS automatically and does not buffer responses by default.

## Monitoring

- **Health**: `GET /health` — uptime, version, entity counts
- **Readiness**: Returns 200 when the server is ready to accept requests

## Backup

Datomic Local stores data as files under the `noumenon-data` Docker volume.

```bash
# Backup
docker compose stop
docker run --rm -v noumenon-data:/data -v $(pwd):/backup alpine \
  tar czf /backup/noumenon-backup-$(date +%Y%m%d).tar.gz /data
docker compose start

# Restore
docker compose stop
docker run --rm -v noumenon-data:/data -v $(pwd):/backup alpine \
  sh -c "rm -rf /data/* && tar xzf /backup/noumenon-backup-YYYYMMDD.tar.gz -C /"
docker compose start
```

## Upgrading

```bash
docker compose pull
docker compose up -d
```

The database schema is migrated automatically on startup (idempotent).

## Webhook Setup (GitHub)

1. Set `NOUMENON_WEBHOOK_SECRET` in your `.env`
2. In GitHub repo settings > Webhooks > Add webhook:
   - URL: `https://noumenon.example.com/api/webhook/github`
   - Content type: `application/json`
   - Secret: same value as `NOUMENON_WEBHOOK_SECRET`
   - Events: Just the push event
3. Each push to main triggers an automatic refresh of the knowledge graph
