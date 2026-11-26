# VPS demo deployment — Ubuntu, Docker Compose, Nginx

This guide deploys the project alongside the existing services on the Kubijatra
Ubuntu VPS. It keeps this project in `/opt/fuel-management-system`, publishes
its containers only on loopback, and uses a separate Nginx virtual host for
`fuel-demo.kubijatra.com`.

> **Demo access boundary:** the application has no built-in authentication or
> roles. The Nginx Basic Auth step below protects the whole UI/API with one
> shared demo credential. Any person who receives that credential can use
> approval actions. Give it only to trusted reviewers; it is not a substitute
> for application user identity or role-based authorization.

## Current live URLs

The VPS deployment has been configured and these endpoints were opened
successfully on 26 November 2026:

| Service | URL | Access |
|---|---|---|
| Fuel Supply Intelligence | [https://fuel-demo.kubijatra.com/](https://fuel-demo.kubijatra.com/) | Nginx Basic Auth |
| Grafana dashboards | [https://grafana-demo.kubijatra.com/dashboards](https://grafana-demo.kubijatra.com/dashboards) | Nginx Basic Auth, then Grafana login |
| Prometheus query UI | [https://prometheus-demo.kubijatra.com/query](https://prometheus-demo.kubijatra.com/query) | Nginx Basic Auth |

The public VPS address is `103.174.50.68`, but use the HTTPS hostnames above:
Nginx routes each hostname to the correct loopback-only service. The browser
first asks for the shared Nginx credential (`supervisor`); Grafana then asks
for its own Grafana account. Never publish either password in this repository.

## VPS facts and isolation plan

Inspection on 26 November 2026 found:

| Item | Existing state | Fuel project setting |
|---|---|---|
| Project directory | `/opt` contains other projects | `/opt/fuel-management-system` |
| Nginx | Active on ports 80/443; existing `kubijatra.com` vhost | Add a separate `fuel-demo.kubijatra.com` vhost; do not edit existing vhosts |
| Java service | Host port 8080 already occupied | Backend published on `127.0.0.1:18080` |
| PostgreSQL | Existing container on `127.0.0.1:5432` | Fuel DB on `127.0.0.1:15432` |
| Host resources | 2 vCPU, 3.8 GiB RAM, no swap, 35 GiB disk free at inspection | Build sequentially; monitor memory and disk |
| DNS | Cloudflare authoritative; demo and monitoring hostnames resolve to this VPS | Each hostname routes through its own Nginx virtual host |

Fuel app mappings are loopback-only: frontend `15173`, backend `18080`,
simulator `18000`, PostgreSQL `15432`, Prometheus `19090`, Grafana `13000`.
Do not open these ports in UFW/provider firewall. Only SSH, HTTP and HTTPS are
needed publicly. The names `fuel-*`, Compose project `fuel-platform`, and
volume names are separate from the inspected `cou-bus-tracker-*` containers.

The remote clone was `main` at `c49f44e` when inspected. Deploy only after the
required changes have been pushed and verified in GitHub; then update the clone
to that commit. Do not copy `.env` into Git or paste its values into chat.

## 1. Check out the intended revision

Run on the VPS after the deployment commit is pushed:

```bash
cd /opt/fuel-management-system
git status --short --branch
git fetch origin
git pull --ff-only origin main
git log -1 --oneline
```

If the local tree is not clean, stop and inspect it; do not reset or overwrite
it blindly.

## 2. Create the private environment file

The VPS operator should create strong, unique credentials and retain the
existing `.env` with mode `600`. Never replace it by copying the example over
it. Required values include:

```dotenv
DB_PASSWORD=<unique-random-secret>
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=<different-unique-random-secret>
GRAFANA_ROOT_URL=https://grafana-demo.kubijatra.com/
GRAFANA_DOMAIN=grafana-demo.kubijatra.com
DB_PORT=15432
BACKEND_PORT=18080
FRONTEND_PORT=15173
SIMULATOR_PORT=18000
PROMETHEUS_PORT=19090
GRAFANA_PORT=13000
SIMULATOR_START_MODE=paused
```

The compose file keeps host mappings on `127.0.0.1`; do not change them to
`0.0.0.0`. Check without printing secrets:

```bash
chmod 600 .env
docker compose config -q
docker compose config --format json \
  | python3 -c 'import json,sys; d=json.load(sys.stdin); [print(n, s.get("ports", [])) for n,s in d["services"].items()]'
```

Confirm that only the six loopback mappings above appear.

## 3. Build in stages and watch resources

The VPS already hosts another application and has 4 GiB RAM with no swap.
Building Java and frontend images together may cause memory pressure. Build
separately and check the current containers after each build:

```bash
free -h
df -h /
docker compose build backend
docker compose build frontend
docker compose images
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
free -h
```

If memory becomes critically low or an existing service degrades, stop and
inspect before continuing. Do not prune global Docker images/volumes: those may
belong to existing applications.

## 4. Start only this Compose project

First validate that the project container names are not already in use. Then:

```bash
cd /opt/fuel-management-system
docker compose up -d
docker compose ps
docker compose logs --tail=100 backend frontend simulator-api
```

Compose operates on this directory's `fuel-platform` project. Do not run
`docker compose down` from another application's directory, and never use
`docker compose down -v` for this demo. The latter deletes this project's
database/Grafana volumes.

Check local-only endpoints from the VPS:

```bash
curl -fsS http://127.0.0.1:18080/api/system-status
curl -I http://127.0.0.1:15173/
```

Expect backend/database/simulator `UP`, a `FRESH` snapshot and
`decision_eligible: true`. Also confirm existing services remain healthy:

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
sudo nginx -t
```

## 5. Add separate password-protected Nginx sites

Keep the existing `/etc/nginx/sites-available/kubijatra.com.conf` unchanged.
Install the htpasswd utility if needed, and create a separate password file:

```bash
sudo apt update
sudo apt install -y apache2-utils
sudo htpasswd -cB /etc/nginx/.htpasswd-fuel-demo supervisor
sudo chmod 640 /etc/nginx/.htpasswd-fuel-demo
sudo chown root:www-data /etc/nginx/.htpasswd-fuel-demo
```

The password prompt is interactive; do not put the password in shell history.
Create a new HTTP-only site file:

```bash
sudo tee /etc/nginx/sites-available/fuel-demo.kubijatra.com.conf >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name fuel-demo.kubijatra.com;

    auth_basic "Fuel Supply Intelligence demo";
    auth_basic_user_file /etc/nginx/.htpasswd-fuel-demo;

    location / {
        proxy_pass http://127.0.0.1:15173;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 90s;
        proxy_buffering off;
    }
}
NGINX

sudo ln -s /etc/nginx/sites-available/fuel-demo.kubijatra.com.conf \
  /etc/nginx/sites-enabled/fuel-demo.kubijatra.com.conf
sudo nginx -t
sudo systemctl reload nginx
```

If the symlink already exists, inspect it rather than replacing it. Verify the
existing sites still load and the new hostname prompts for Basic Auth over
HTTP before proceeding to TLS.

### Monitoring hostnames (configured)

The `grafana-demo` and `prometheus-demo` DNS records resolve to this VPS. The
following is the initial setup pattern (the live host blocks have already been
added in a separate Nginx site file, leaving the existing vhosts untouched).
Both monitoring hosts reuse `/etc/nginx/.htpasswd-fuel-demo`:

```nginx
server {
    listen 80;
    listen [::]:80;
    server_name grafana-demo.kubijatra.com;
    auth_basic "Fuel monitoring";
    auth_basic_user_file /etc/nginx/.htpasswd-fuel-demo;
    location / {
        proxy_pass http://127.0.0.1:13000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

For Prometheus use `server_name prometheus-demo.kubijatra.com` and
`proxy_pass http://127.0.0.1:19090`. Keep Basic Auth enabled: Prometheus has no
built-in user login. Grafana keeps its own login behind the Nginx password.
The two monitoring sites are now configured in a separate Nginx file, protected
with the shared Basic Auth file, and use the loopback-only upstreams shown
above. The example block is retained as a reference. Run `sudo nginx -t` before
reloading Nginx after any future edits.

Grafana uses its public hostname as its root URL. These non-secret values are
configured in the private VPS `.env`:

```dotenv
GRAFANA_ROOT_URL=https://grafana-demo.kubijatra.com/
GRAFANA_DOMAIN=grafana-demo.kubijatra.com
```

The Compose file maps those to Grafana's `GF_SERVER_ROOT_URL` and
`GF_SERVER_DOMAIN`. Apply only Grafana's environment update with
`docker compose up -d grafana`; do not recreate or stop the unrelated app.

## 6. TLS issuance and renewal checks

For first-time HTTP-01 issuance, each DNS record must resolve to this VPS and
port 80 must be reachable in both UFW and the VPS provider firewall. If
Cloudflare proxying interferes with validation, temporarily use DNS-only mode
for issuance. Certificates for all three demo hostnames have now been issued.

```bash
# Initial issuance commands (already completed on the current VPS):
sudo certbot --nginx -d fuel-demo.kubijatra.com
sudo certbot --nginx -d grafana-demo.kubijatra.com
sudo certbot --nginx -d prometheus-demo.kubijatra.com
sudo nginx -t
sudo systemctl reload nginx
sudo certbot renew --dry-run
```

Certbot may update the new site file to add the certificate and HTTP-to-HTTPS
redirect. Inspect the diff/virtual host, then verify:

```bash
curl -I https://fuel-demo.kubijatra.com
curl -I https://grafana-demo.kubijatra.com/dashboards
curl -I https://prometheus-demo.kubijatra.com/query
```

The response should ask for Basic Auth (HTTP 401 without credentials). Test in
a private browser window, authenticate, and verify the dashboard and API work.
Do not expose Grafana, Prometheus, PostgreSQL, backend, or simulator via
additional public proxy routes unless separately protected.

## 7. Backups, updates and rollback

Before upgrades, back up PostgreSQL to a location outside the container and
outside any directory served by Nginx:

```bash
cd /opt/fuel-management-system
docker compose exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' \
  > /root/fuel-platform-$(date +%F-%H%M).sql
```

Record the current revision, then update only after the new commit is pushed:

```bash
git rev-parse HEAD
git pull --ff-only origin main
docker compose config -q
docker compose build backend frontend
docker compose up -d
docker compose ps
```

For rollback, check out the previous known-good commit and rebuild/recreate
this project's services. Back up the database first; schema migrations may
not be reversible. Never delete named volumes as a rollback shortcut.

## 8. Operations and costs

```bash
cd /opt/fuel-management-system
docker compose ps
docker compose logs -f backend
sudo nginx -t
sudo systemctl status nginx --no-pager
```

Cloudflare proxy is currently enabled for `fuel-demo.kubijatra.com`. In the
Cloudflare dashboard, verify **SSL/TLS → Overview → Full (strict)** so traffic
from Cloudflare to this VPS is encrypted and validates the Certbot origin
certificate. Do not use Flexible mode. Maintain UFW/provider rules for SSH, 80
and 443 only. Schedule database backups and verify restore on a
separate test database before relying on them. Watch RAM, swap, disk and
container health during a demo; the earlier RUNNING load probe found an
11.77-second `/api/risk` p95 and a separate stress attempt exhausted the
simulator connection pool.

## VPS deployment verification — 26 November 2026

- Repository updated to `03ea084` (`Document VPS deployment status`).
- All six Fuel Compose services started. Backend, frontend, PostgreSQL and
  simulator health checks reported healthy; Prometheus `/‑/healthy` returned
  healthy and Grafana `/login` returned HTTP 200 after startup.
- Fuel host ports were loopback-only and did not collide with the existing
  Java service on 8080 or the bus-tracker PostgreSQL on 5432.
- Nginx returned a Basic Auth challenge for `fuel-demo.kubijatra.com` over
  HTTPS. Certbot renewal dry-run succeeded for both the new certificate and
  the existing site certificate.
- The operator confirmed the live Grafana dashboards at
  `https://grafana-demo.kubijatra.com/dashboards` and Prometheus query page at
  `https://prometheus-demo.kubijatra.com/query`.
- `curl -I` returned HTTP/2 401 with the expected `Fuel monitoring` Basic Auth
  challenge for both monitoring hostnames. This confirms the HTTPS virtual
  hosts and access gate are active; authenticate in a browser to use them.
- Existing `cou-bus-tracker` containers remained running.
- Cloudflare currently proxies the new hostname. Verify its SSL/TLS mode is
  `Full (strict)` in the dashboard; that setting was not observable from the
  VPS shell evidence.

The local app itself does not authenticate users; the shared Nginx password is
only a temporary reviewer access barrier, not per-user identity or roles.
