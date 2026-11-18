#!/usr/bin/env bash
set -euo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if [[ -e .env || -e redis/conf/redis.conf ]]; then
  echo '已有密码文件或 Redis 配置，已停止，避免覆盖现有凭据。' >&2
  exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo '找不到 openssl，请先安装 openssl 后重试。' >&2
  exit 1
fi

umask 077
chmod 700 .
mkdir -p redis/conf

mysql_root_password=$(openssl rand -hex 24)
mysql_app_password=$(openssl rand -hex 24)
redis_password=$(openssl rand -hex 24)

printf 'MYSQL_ROOT_PASSWORD=%s\nMYSQL_PASSWORD=%s\nREDIS_PASSWORD=%s\n' \
  "$mysql_root_password" "$mysql_app_password" "$redis_password" > .env
printf 'bind 0.0.0.0\nprotected-mode yes\nport 6379\nappendonly yes\ndir /data\nrequirepass %s\n' \
  "$redis_password" > redis/conf/redis.conf
unset mysql_root_password mysql_app_password redis_password

# The project directory is private on the host; the following files and
# directories must still be readable by non-root users inside containers.
chmod 600 .env
chmod 755 mysql/init nginx/web/dist
chmod 644 mysql/init/01-init.sql redis/conf/redis.conf nginx/conf/nginx.conf nginx/web/dist/index.html

echo '初始化完成。密码保存在当前目录的 .env 中，请勿公开或提交到版本库。'
