# Bite Wear 中间件部署

这套文件用于课程示例的学习和开发环境：MySQL 8.4.2、Redis 7.0.15、Nginx 1.24.0。要求 Linux 服务器已经安装 Docker Engine、Docker Compose 插件和 OpenSSL，且 Docker 服务已运行。按照课程版本固定的镜像较旧，正式上线前应选择仍受维护的版本并完成兼容性验证。

## 部署前检查

在空闲环境中使用。先检查 80、3306、6379 端口，以及同名容器、网络和 Compose 项目的卷是否已被使用。如果已有数据或部署，请先确认如何迁移或共存，不要清理现有数据。

```bash
sudo ss -lntp
sudo docker ps -a --filter name=bite_wear
sudo docker volume ls --filter name=bite_wear
sudo docker network ls --filter name=bite_wear_network
```

从 Windows 复制本目录到服务器用户的家目录后，登录服务器，在目录内执行：

```bash
cd ~/middleware-deploy
bash prepare.sh
sudo docker compose -p bite_wear config --quiet
sudo docker compose -p bite_wear pull
sudo docker compose -p bite_wear up -d
sudo docker compose -p bite_wear ps
```

`prepare.sh` 只应在首次部署执行，它会生成 `.env` 和 `redis/conf/redis.conf`，并拒绝覆盖现有文件。密码保存在 `.env`，不要把这个文件或 Redis 配置发给他人、提交到版本库。服务器上 Docker 操作如果不需要 sudo，可以省略；如果 root 环境没有 sudo，也去掉 sudo。

## 验证

首次启动 MySQL 需要一些时间。MySQL 和 Redis 应显示 `healthy`，Nginx 应处于运行状态。运行：

```bash
sudo docker compose -p bite_wear exec -T bite_wear-mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=tcp -h 127.0.0.1 -u "$MYSQL_USER" -D bite_wear -Nse "SELECT DATABASE()"'
sudo docker compose -p bite_wear exec -T bite_wear-redis redis-cli ping
sudo docker compose -p bite_wear exec -T bite_wear-web nginx -t
curl -fsS http://127.0.0.1/
```

分别应看到 `bite_wear`、`PONG`、Nginx 配置测试成功和一段测试页 HTML。失败时可以查看 `sudo docker compose -p bite_wear logs --tail=100`，不要公开密码文件或其他敏感信息。

## 后续接入

- MySQL 和 Redis 仅监听服务器的 `127.0.0.1`。服务器本机应用分别使用 3306、6379；其他 Docker 容器加入 `bite_wear_network` 后，可使用 `bite_wear-mysql:3306`、`bite_wear-redis:6379`。本地开发需要 SSH 端口转发。
- 用户名为 `bite`，数据库为 `bite_wear`，MySQL 与 Redis 的实际密码从 `.env` 获取并配置到后端。SQL 脚本还按课程授予 `bite` 全局复制权限，可读取跨数据库的 binlog；若项目不需要这些权限，应在首次启动前删除相应 GRANT 行。
- 课程截图没有业务建表和数据 SQL。本配置只建立库、用户和权限；拿到项目 SQL 后另行导入。初始 SQL 和 MySQL 初始化环境变量只在全新的 MySQL 数据卷上生效。
- Nginx 的 `nginx/web/dist` 是测试页；用实际前端的构建输出替换。当前配置只提供静态文件，不包含后端 API 代理或 HTTPS。
- MySQL 和 Redis 数据保存在 Docker 命名卷中。普通 `docker compose down` 不会移除这些卷；不要使用 `down -v` 或清理卷来排错，除非已确认需要删除数据并已备份。
- 如服务器启用 SELinux 导致绑定挂载文件访问被拒绝，需要检查实际审计日志和文件标签后再调整挂载标签，勿直接关闭 SELinux。
