#!/bin/bash
set -e

# 官方镜像默认以 root 启动,这里切到 mongodb 用户(uid 999)运行,
# 保证对 /data/db、/data/configdb 有正确属主。
if [ "$(id -u)" = "0" ]; then
  exec gosu mongodb "$0" "$@"
fi

KEYFILE=/data/configdb/keyfile
DBPATH=/data/db

# 首次生成 keyfile:MongoDB 规定「副本集 + 开启认证」必须配 keyFile 做成员间认证
if [ ! -f "$KEYFILE" ]; then
  head -c 756 /dev/urandom | base64 > "$KEYFILE"
  chmod 400 "$KEYFILE"
fi

# 阶段 1:无认证启动(不能配 --keyFile,否则会隐式开启 authorization,导致后续建用户被拒绝)
mongod --replSet rs0 --bind_ip_all --dbpath "$DBPATH" &
PID=$!

echo "[init] 等待 MongoDB 启动 ..."
until mongosh --quiet --eval 'db.runCommand({ping:1})' >/dev/null 2>&1; do
  sleep 2
done

echo "[init] 初始化副本集 rs0 ..."
mongosh --quiet --eval '
let already = false;
try { already = rs.status().ok === 1; } catch (e) { already = false; }
if (!already) { rs.initiate({ _id: "rs0", members: [{ _id: 0, host: "localhost:27017" }] }); }
'

echo "[init] 等待选出 primary ..."
until mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null | grep -q '^true$'; do
  sleep 2
done

echo "[init] 创建 root 用户 ..."
mongosh --quiet --eval '
const user = process.env.MONGO_INITDB_ROOT_USERNAME || "root";
const pwd = process.env.MONGO_INITDB_ROOT_PASSWORD || "root";
if (!db.getSiblingDB("admin").getUser(user)) {
  db.getSiblingDB("admin").createUser({ user: user, pwd: pwd, roles: [{ role: "root", db: "admin" }] });
}
'

# 阶段 2:关闭无认证实例,以「认证模式」正式启动,作为容器主进程
kill "$PID"
wait "$PID" 2>/dev/null || true

echo "[init] 以认证模式正式启动 ..."
exec mongod --replSet rs0 --bind_ip_all --keyFile "$KEYFILE" --auth --dbpath "$DBPATH"
