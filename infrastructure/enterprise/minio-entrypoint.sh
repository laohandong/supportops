#!/bin/sh
set -eu
export MINIO_ROOT_USER=supportops-storage
MINIO_ROOT_PASSWORD=$(cat /run/secrets/supportops.knowledge.storage.secret-key)
export MINIO_ROOT_PASSWORD
exec minio server /data
