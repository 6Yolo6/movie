#!/bin/sh
set -eu
: "${REDIS_PASSWORD:?REDIS_PASSWORD is required}"
umask 077
# Hash the secret rather than putting it in argv or interpolating it into Redis syntax.
password_hash=$(printf '%s' "$REDIS_PASSWORD" | sha256sum)
password_hash=${password_hash%% *}
printf 'user default on #%s ~* &* +@all -@dangerous\n' "$password_hash" > /tmp/gying-users.acl
unset password_hash REDIS_PASSWORD
cat > /tmp/gying-redis.conf <<'CONFIG'
bind 0.0.0.0
protected-mode yes
port 6379
aclfile /tmp/gying-users.acl
save ""
appendonly no
maxmemory 128mb
maxmemory-policy noeviction
loglevel notice
CONFIG
exec redis-server /tmp/gying-redis.conf
