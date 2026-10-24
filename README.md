# 图书馆座位预约管理系统

PC端学生预约与管理员维护。当前迁移分支使用 Java21、Spring Boot3.5.16、MyBatis3.0.5、MySQL8、Vue3/JavaScript、Element Plus和Redis；原生fetch，同源Session/CSRF。

学生可以筛选与键盘选座、预约、取消、签到、暂离、返回、签退，查询本人事件/违约并修改密码。管理员可以维护学生、场地、特殊开放安排、规则版本、公告，关闭预约、撤销违约并查看统计和审计。

## 运行

需要JDK21、Node22.12+、MySQL8和Redis。Windows：

```powershell
Copy-Item .env.example .env
# 编辑.env中的两个密码；Compose不会覆盖已有数据库卷的账号密码。
docker compose up -d
./scripts/start-local.ps1 -Demo
```

访问 http://127.0.0.1:18082 。`-Demo`只用于专用演示库：空用户表才创建admin / Admin123!和20260001—20260020 / Student123!。默认启动不生成演示账号；迁移已有库不要加Demo。

开发前端：`npm ci --prefix web`，`npm run dev --prefix web`，访问18083，代理18082的API和图片。正式构建：`npm run build --prefix web`后`mvn verify`，web/dist随Boot打包。Linux启动见[部署说明](docs/deployment.md)。

```powershell
./scripts/stop-local-app.ps1
./scripts/stop-local-db.ps1
```

停止服务保留MySQL卷。数据库备份不提交GitHub，升级与回退见[迁移手册](docs/MIGRATION.md)。

## 界面

![Vue选座页](docs/images/vue/booking.png)

![管理员概览](docs/images/vue/admin.png)

[登录页](docs/images/vue/login.png) · [预约详情](docs/images/vue/detail.png) · [表单错误恢复](docs/images/vue/admin-errors.png)。旧版截图保留在docs/images根目录；图书馆照片为环境示意，授权来源见[素材说明](src/main/resources/static/images/SOURCES.md)。

## 正确性边界

- MySQL事务先锁write_guard(id=1)，所有业务写共用这一协议。一名学生同时最多1条有效预约，跨阅览室/不重叠的未来时段也不能再约。
- `(user_id,request_key)`唯一约束实现重试幂等；同key异参数409。时段为半开区间，预约绑定创建时rule_id。
- GET不触发超时写扫描；到期状态计算展示并标识待归档。定时任务和启动补偿按最多200条候选处理，预约写入会补处理本人和目标座位的超时记录。
- Redis只缓存场地、座位静态属性和规则展示，TTL120—150秒、提交后失效、失败回源；最终预约/权限始终读取MySQL。
- Session为单实例内存会话，Cookie HttpOnly/SameSite=Lax，HTTPS应设置COOKIE_SECURE=true。密码保留BCrypt；登录轮换会话ID/token，密码/启停版本变化使旧会话失效。

## 验证与文档

[测试证据](docs/TESTING.md) · [架构与状态](docs/architecture-overview.md) · [API清单](docs/API.md) · [升级回退](docs/MIGRATION.md) · [面试说明](docs/INTERVIEW.md) · [持续集成](.github/workflows/ci.yml)。

```powershell
npm ci --prefix web
npm run format:check --prefix web
npm test --prefix web
npm run build --prefix web
# 先准备独立library_seat_test数据库，设置TEST_DB_URL/USER/PASSWORD。
mvn -B -ntp verify
# 另起专用演示库应用，再执行浏览器测试；不得指向实际使用库。
npm run test:e2e --prefix web
```

这是课程/作品集项目。演示种子120座位、20学生不代表学校真实规模；20人竞争一致性测试不代表生产QPS。没有实现Redis抢座锁、分布式Session、微服务或MQ。
