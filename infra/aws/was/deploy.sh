#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: deploy.sh <built-backend-jar>" >&2
  exit 2
fi

app_dir=/opt/itda/current
secret_file=/opt/itda/secrets/app.env
if [ ! -f "$secret_file" ]; then
  echo "missing server-side secret file: $secret_file" >&2
  exit 1
fi

install -m 0644 "$1" "$app_dir/app.jar"
docker build -t itda-backend:current -f "$app_dir/Dockerfile" "$app_dir"
docker compose -f "$app_dir/compose.yaml" up -d
docker compose -f "$app_dir/compose.yaml" ps
