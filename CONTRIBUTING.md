# 参与维护

[返回首页](README.md) · [部署说明](docs/deployment.md) · [测试说明](docs/TESTING.md)

## 反馈问题

请使用[问题反馈模板](https://github.com/17elr/library-seat-reservation-system/issues/new?template=bug_report.md)，提供分支 / 提交、使用角色、页面、复现步骤，以及期望和实际结果。界面问题可附截图，环境信息包括 Java、Node、MySQL、Redis 与浏览器版本。

提交前移除密码、Cookie、token、数据库备份和真实学生信息。

## 提交改动

1. 从目标开发分支创建功能分支，说明具体问题和修改后的行为。
2. 沿用已有 Java / Vue 结构；修改预约、认证、规则或缓存时，同时更新对应验证与文档。
3. 提交 PR，使用仓库模板记录验证结果和数据兼容性影响，保留主分支供审核。

数据库测试仅连接独立的 `library_seat_test`，不要指向实际使用库。浏览器回归仅连接专用演示库。

## 运行检查

```powershell
npm ci --prefix web
npm run format:check --prefix web
npm run format:java:check --prefix web
npm test --prefix web
npm run build --prefix web
# 准备测试库并设置 TEST_DB_URL / TEST_DB_USER / TEST_DB_PASSWORD
mvn -B -ntp verify
```

页面行为变更后，再运行专用演示库上的 `npm run test:e2e --prefix web`。仅文档与素材变更时，核对链接、截图和 GitHub 渲染即可。

## 业务改动的核对点

预约写入遵守统一 guard 与 MySQL 事务；每人最多一条有效预约；保留原 rule_id 与重试 key 语义。GET 保持只读，Redis 保持资料缓存边界。数据库迁移先在副本备份并验证回退，操作方法见[迁移手册](docs/MIGRATION.md)。
