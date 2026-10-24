# 构建和部署

## 环境

Java21、Node22.12+、MySQL8、Redis7.4（Compose配置），Python3与mysql/mysqldump客户端用于数据工具。生产连接使用专用library用户；测试只能连接library_seat_test，测试清表前会断言库名。

`.env.example`复制为`.env`并修改密码，`docker compose up -d --wait`启动仅绑定127.0.0.1的13318/16378。不要修改机器原MySQL服务；若端口已被其他项目占用，应先核对所有者。`docker compose stop`保留卷，不使用down -v。

## Windows

`./scripts/start-local.ps1 -Demo`先构建Vue和Boot，再以隐藏进程启动18082；仅专用演示库加Demo。脚本读取.env且不覆盖已设置的环境变量。日志.local/app.log、app-error.log；停止用stop-local-app.ps1，会核对PID和jar路径，防止停错进程。重新构建前应停止应用，Windows会锁住正在使用的jar。

## Linux / macOS

```bash
npm ci --prefix web
npm test --prefix web
npm run build --prefix web
# TEST_DB_*单独配置到library_seat_test后验证
mvn -B -ntp verify
# 密码与Compose创建时一致，通过部署环境注入，不写进Git。
export DB_USER=library
export DB_PASSWORD='your-local-password'
export DB_URL='jdbc:mysql://127.0.0.1:13318/library_seat?serverTimezone=Asia/Shanghai&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false'
java -jar target/library-seat-0.1.0.jar --spring.profiles.active=demo
```

最后一行只用于演示；已有数据/实际部署去掉demo。首次实际库导入既有用户、BCrypt哈希和资料，不通过演示种子建立生产账号。Flyway启动迁移，默认不重置数据。

## 开发和同源交付

后端18082，`npm run dev --prefix web`固定前端18083，代理/api和/images。打包从web/dist复制到jar的static，不需要单独前端服务器。已知Vue页面刷新转index；不存在API/图片返回404。

HTTPS反向代理时设置COOKIE_SECURE=true，转发到回环18082。跨域认证和分布式Session不在本轮范围；默认内存会话重启后需重新登录。Redis断开时有警告但资料回源，超时300ms；不要把Redis故障回源误认作数据库故障也会成功。

## CI

Actions用Java21、Node22、MySQL8和Redis7.4，执行Vue格式/单元测试/构建、mvn verify和独立demo库的Playwright Chromium测试。日志与报告作为artifact保存。浏览器测试会创建并取消演示预约，只能指向专用演示库；设置PLAYWRIGHT_CHANNEL=msedge可用本机Edge验证。
