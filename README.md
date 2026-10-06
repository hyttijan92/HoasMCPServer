# HOAS Booking MCP Server

An [MCP](https://modelcontextprotocol.io) server for the HOAS booking service
([booking-hoas.tampuuri.fi](https://booking-hoas.tampuuri.fi/)). It lets an AI assistant such as Claude check
availability and book or cancel laundry machines, dryers and saunas on your behalf.

> Unofficial project, not affiliated with HOAS or Tampuuri. The booking site has no API, so the server logs in with
> your account and reads the HTML pages like a browser would. It can break if the site changes.

## Tools

| Tool | Description |
|---|---|
| `list_services` | Your bookable services (laundry room, dryers, saunas) with their service ids |
| `get_timetable` | One day's timetable for a service: each machine with its `machineId` and slots marked `FREE`, `RESERVED`, `OWN` or `OTHER`, plus the bookable date range and your usage against the limit |
| `list_my_reservations` | Your upcoming reservations with their `reservationId` |
| `reserve_slot` | Book a free slot, then check the timetable to confirm it is yours |
| `cancel_reservation` | Cancel one of your own reservations, then check that it is gone |
| `list_announcements` | Notices from the booking service, e.g. a machine out of order |

`reserve_slot` and `cancel_reservation` change your bookings. Their descriptions tell the assistant to confirm with
you first.

## Requirements

- A HOAS booking account (username and password)
- Docker, or Java 21+ to run it without Docker

## Getting started

The project is in the `hoas/` folder. All paths and commands in this README are relative to it:

```bash
git clone https://github.com/hyttijan92/HoasMCPServer.git
cd HoasMCPServer/hoas
```

## Configuration

Settings are read from environment variables, or from a `.env` file in the `hoas/` folder. `.env` is gitignored and
excluded from the Docker image.

| Variable | Required | Description |
|---|---|---|
| `HOAS_USERNAME` | yes | Your HOAS booking username |
| `HOAS_PASSWORD` | yes | Your HOAS booking password |
| `HOAS_MCP_API_KEY` | yes | Key every MCP client must send. At least 32 characters; the server will not start without it |
| `HOAS_MCP_DOMAIN` | for public hosting | Domain Caddy serves HTTPS on, e.g. `hoas.example.com` |

Create the file:

```bash
cat > .env <<EOF
HOAS_USERNAME=your-username
HOAS_PASSWORD=your-password
HOAS_MCP_API_KEY=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
EOF
chmod 600 .env
```

Write the values plainly, without quotes.

## Run locally

With Docker:

```bash
docker build -t hoas-mcp .
docker run -d --name hoas-mcp --env-file .env -p 127.0.0.1:8080:8080 hoas-mcp
```

Or with Java:

```bash
./mvnw spring-boot:run
```

The MCP endpoint is `http://localhost:8080/mcp` (streamable HTTP).

## Connect a client

Every request must carry the API key as `Authorization: Bearer <key>` (or `X-API-Key: <key>`). The key is never
accepted in the URL. Requests without it get a `401`.

**Claude Code**

```bash
claude mcp add --transport http hoas http://localhost:8080/mcp \
  --header "Authorization: Bearer <your HOAS_MCP_API_KEY>"
```

**Claude apps (web, desktop, mobile)**

These connect from Anthropic's servers, so the server must be reachable on the public internet over HTTPS (see below).
In claude.ai, go to **Customize > Connectors > Add custom connector**:

- **URL:** `https://<your-domain>/mcp`
- **Authentication:** No sign-in
- **Request headers:** `authorization` with the value `Bearer <your HOAS_MCP_API_KEY>`

The **Request headers** section is a beta feature and is not available on every account. Without it, the Claude apps
need OAuth, which this server does not implement.

## Host it publicly

`compose.yaml` runs the server behind [Caddy](https://caddyserver.com), which serves HTTPS on your domain and obtains
the certificate automatically. The MCP server itself is not published on the host.

1. Point a DNS record for your domain at the host and open ports 80 and 443.
2. Add `HOAS_MCP_DOMAIN=hoas.example.com` to `.env`.
3. Start it:

   ```bash
   docker compose up -d --build
   ```

The `Caddyfile` contains a commented-out rule that only lets Anthropic's servers through. Enable it for an extra layer
if you only use the Claude apps; add your own IP if you also use Claude Code against the public address.

To change the API key, update `HOAS_MCP_API_KEY`, restart, and re-add the connector in each client.

## Deploy to Azure

`hoas/deploy/azure` contains ARM templates and a script that run the server on
[Azure Container Apps](https://learn.microsoft.com/azure/container-apps/). Azure provides the HTTPS address and
certificate, so Caddy and a domain of your own are not needed.

```bash
az login
deploy/azure/deploy.sh
```

The script reads the secrets from `.env`, then:

1. creates the resource group `hoas-mcp-rg` in `swedencentral`
2. deploys `registry.json`: a container registry (Basic tier)
3. builds the image in Azure and pushes it to the registry
4. deploys `app.json`: a log workspace, the Container Apps environment, a managed identity that may pull the image,
   and the app itself with the three secrets
5. prints the MCP endpoint, `https://hoas-mcp.<...>.azurecontainerapps.io/mcp`

Run it again to deploy a new version. Settings are environment variables:

| Variable | Default | Description |
|---|---|---|
| `RESOURCE_GROUP` | `hoas-mcp-rg` | Resource group that holds everything |
| `LOCATION` | `swedencentral` | Azure region |
| `APP_NAME` | `hoas-mcp` | App name, and the first part of its hostname |
| `MIN_REPLICAS` | `0` | `0` scales to zero when idle (cheapest; the first request after a pause waits for a cold start). `1` keeps it always on |
| `ALLOWED_IP_RANGES` | empty | Space-separated CIDR ranges allowed in, e.g. `160.79.104.0/21` for Anthropic's servers. Empty means no IP restriction |

The app never runs more than one replica, because the HOAS login and the MCP sessions are kept in memory.

Your account needs permission to create role assignments in the resource group (Owner or User Access Administrator),
because the template grants the app's identity pull access to the registry.

```bash
az containerapp logs show -g hoas-mcp-rg -n hoas-mcp --follow   # logs
az group delete -n hoas-mcp-rg                                  # remove everything
```

## Security notes

- Anyone with the API key can book and cancel in your name. Keep `.env` private and only expose the server over HTTPS.
- Your HOAS password is only sent to the HOAS site. It is not written to logs or baked into the image.
- Rejected requests are logged with the caller's address.

## How it works

- `HoasClient` keeps a logged-in browser-like session (cookies and CSRF token) and logs in again when it expires.
- `HoasParser` turns the timetable pages into data with [jsoup](https://jsoup.org).
- `HoasBookingService` implements the booking flows. The site gives no clear success message, so bookings and
  cancellations are verified by reading the timetable afterwards.
- `HoasTools` exposes the MCP tools; `ApiKeyFilter` guards the endpoint.

Built with Spring Boot 4 and Spring AI's MCP server starter.

### Things to know about the booking site

- Laundry and dryers have a weekly limit; saunas have a monthly limit and a shorter booking window.
- Next month's laundry slots open on the 1st of the month at 00:00.
- A cancellation less than 12 hours before the start still counts against your limit.
- A machine's `machineId` is only known on days when it has at least one free slot.

## Development

```bash
./mvnw test
```

The parser tests run against a saved, anonymised timetable page in `hoas/src/test/resources`. The Docker build skips the
tests, so run them separately when you change the parser.

## License

[MIT](LICENSE)
