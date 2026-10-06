#!/usr/bin/env bash
# Deploys the HOAS MCP server to Azure Container Apps.
#
#   az login                      # once
#   deploy/azure/deploy.sh        # first deployment and every later update
#
# Reads HOAS_USERNAME, HOAS_PASSWORD and HOAS_MCP_API_KEY from .env in the project root.
# Optional overrides: RESOURCE_GROUP, LOCATION, APP_NAME, MIN_REPLICAS (0 or 1),
# ALLOWED_IP_RANGES (space-separated CIDRs, e.g. "160.79.104.0/21 203.0.113.7/32").
set -euo pipefail
echo "Deploying HOAS MCP server to Azure Container Apps..."
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HERE="$ROOT/deploy/azure"

RESOURCE_GROUP="${RESOURCE_GROUP:-hoas-mcp-rg}"
LOCATION="${LOCATION:-swedencentral}"
APP_NAME="${APP_NAME:-hoas-mcp}"
MIN_REPLICAS="${MIN_REPLICAS:-0}"
ALLOWED_IP_RANGES="${ALLOWED_IP_RANGES:-}"

env_value() { grep -E "^$1=" "$ROOT/.env" | head -1 | cut -d= -f2-; }
HOAS_USERNAME="$(env_value HOAS_USERNAME)"
HOAS_PASSWORD="$(env_value HOAS_PASSWORD)"
HOAS_MCP_API_KEY="$(env_value HOAS_MCP_API_KEY)"
: "${HOAS_USERNAME:?missing in .env}" "${HOAS_PASSWORD:?missing in .env}" "${HOAS_MCP_API_KEY:?missing in .env}"

# 1. Resource providers (one-time per subscription; harmless to repeat)
for ns in Microsoft.App Microsoft.ContainerRegistry Microsoft.OperationalInsights Microsoft.ManagedIdentity; do
	az provider register --namespace "$ns" --wait
done

# 2. Resource group
az group create --name "$RESOURCE_GROUP" --location "$LOCATION" --output none

# 3. Container registry
ACR_NAME="$(az deployment group create \
	--resource-group "$RESOURCE_GROUP" \
	--name registry \
	--template-file "$HERE/registry.json" \
	--query properties.outputs.acrName.value --output tsv)"

# 4. Build the image in Azure and push it to the registry (.dockerignore keeps .env out of the upload)
IMAGE_TAG="$(date +%Y%m%d%H%M%S)"
az acr build --resource-group "$RESOURCE_GROUP" --registry "$ACR_NAME" --image "hoas-mcp:$IMAGE_TAG" "$ROOT"

# 5. Container app (secrets are passed as secure parameters and stored as Container Apps secrets)
IP_JSON="$(printf '%s\n' $ALLOWED_IP_RANGES | python3 -c 'import sys,json; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))')"
MCP_URL="$(az deployment group create \
	--resource-group "$RESOURCE_GROUP" \
	--name app \
	--template-file "$HERE/app.json" \
	--parameters appName="$APP_NAME" acrName="$ACR_NAME" imageTag="$IMAGE_TAG" \
		minReplicas="$MIN_REPLICAS" allowedIpRanges="$IP_JSON" \
		hoasUsername="$HOAS_USERNAME" hoasPassword="$HOAS_PASSWORD" mcpApiKey="$HOAS_MCP_API_KEY" \
	--query properties.outputs.mcpUrl.value --output tsv)"

echo
echo "MCP endpoint: $MCP_URL"
echo "Send the API key as:  Authorization: Bearer <HOAS_MCP_API_KEY>"
echo "Logs:    az containerapp logs show -g $RESOURCE_GROUP -n $APP_NAME --follow"
echo "Remove:  az group delete -n $RESOURCE_GROUP"
