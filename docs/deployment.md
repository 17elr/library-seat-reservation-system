# 快速启动与部署

## 环境要求

| 依赖 | 要求 |
| --- | --- |
| Java | JDK 21 或以上，编译目标为 Java 21 |
| Maven | 3.6.3 或以上 |
| MySQL | 8.0，现有本地环境验证版本为 8.0.44 |
| 浏览器 | 现代桌面浏览器，建议宽度至少 1280px |

本地脚本面向 Windows PowerShell。其他系统可使用已有 MySQL，按下文环境变量方式启动。

## Windows 本地演示

```powershell
git clone https://github.com/17elr/library-seat-reservation-system.git
cd library-seat-reservation-system
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-local.ps1
```

启动后访问 <http://127.0.0.1:8080>。

脚本选择 `JAVA_HOME` 指定的 JDK，或 `C:/Program Files/Java` 下的 JDK。默认 `java` 指向 Java 8 时，请先配置 `JAVA_HOME` 为 JDK 21 或更新版本。

MySQL 默认安装目录为 `C:/Program Files/MySQL/MySQL Server 8.0/bin`，可通过 `MYSQL_BIN` 覆盖：

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
$env:MYSQL_BIN = 'C:/Program Files/MySQL/MySQL Server 8.0/bin'
$env:LIBRARY_DB_RUNTIME = 'C:/library-seat-runtime'
```

脚本启动独立 MySQL，绑定 `127.0.0.1:13307`，不修改现有 MySQL80 服务。数据目录优先为 `D:/Code/library-seat-dev-13307`，否则使用 `%LOCALAPPDATA%/library-seat-dev-13307`；覆盖后的实际位置记录在 `.local/db-location.txt`。数据库目录应使用英文路径，以避开本机 MySQL 在中文路径下的文件重命名问题。

| 操作 | 命令 |
| --- | --- |
| 只启动数据库 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-local.ps1 -DatabaseOnly` |
| 跳过构建启动 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start-local.ps1 -SkipBuild` |
| 停止应用 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stop-local-app.ps1` |
| 停止数据库 | `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/stop-local-db.ps1` |

停止脚本不会删除数据。重新构建前先停止应用，避免 Windows 占用 JAR 文件。

## 演示账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 管理员 | `admin` | `Admin123!` |
| 学生 | `20260001` 至 `20260020` | `Student123!` |

仅 `local` 配置自动创建演示数据：3 个阅览室、120 个座位、20 名虚构学生。首次初始化后持久保存，重启不覆盖用户修改。上述密码及本地数据库密码是公开演示凭据，仅用于回环地址隔离演示实例。

## 使用已有 MySQL

先创建空库和专用账号。下面使用占位密码，执行前替换：

```sql
CREATE DATABASE library_seat CHARACTER SET utf8mb4;
CREATE USER 'library'@'localhost' IDENTIFIED BY 'REPLACE_WITH_A_STRONG_PASSWORD';
GRANT ALL PRIVILEGES ON library_seat.* TO 'library'@'localhost';
```

示例授权用于首次初始化。正式部署应将建表账号与运行账号分离；使用 `src/main/resources/db/schema.sql` 预建表后，运行账号仅授予所需读写权限。

PowerShell 示例：

```powershell
$env:DB_URL = 'jdbc:mysql://127.0.0.1:3306/library_seat?serverTimezone=Asia/Shanghai&characterEncoding=utf8'
$env:DB_USER = 'library'
$env:DB_PASSWORD = 'REPLACE_WITH_A_STRONG_PASSWORD'
mvn -B -ntp -DskipTests package
java -jar target/library-seat-0.1.0.jar --spring.profiles.active=prod
```

应用默认启动 `local` 配置，连接脚本专用数据库；使用已有数据库时必须显式选择 `prod`。`prod` 默认不创建演示账号，管理员须由部署人员将 BCrypt 密码摘要写入 `sys_user`，不要使用演示密码。上述打包命令跳过测试；测试应另行连接独立测试库执行。

| 环境变量 | 用途 | 默认值 |
| --- | --- | --- |
| `DB_URL` | 非 local 配置的 JDBC 地址 | `jdbc:mysql://127.0.0.1:3306/library_seat?serverTimezone=Asia/Shanghai&characterEncoding=utf8` |
| `DB_USER` | 数据库账号 | `library` |
| `DB_PASSWORD` | 数据库密码 | 空，部署时必须配置 |
| `SERVER_ADDRESS` | 应用监听地址 | `127.0.0.1` |
| `SERVER_PORT` | 应用端口 | `8080` |
| `MYSQL_BIN` | Windows 专用脚本使用的 MySQL bin 目录 | MySQL 8.0 默认安装目录 |
| `LIBRARY_DB_RUNTIME` | Windows 专用脚本的数据存放位置 | 见上文本地演示说明 |

外部访问需配置监听地址，并通过 HTTPS 反向代理及相应的 Session Cookie Secure 配置部署。不要将本地无密码 root 的专用 MySQL 实例或演示账号暴露到公网。MySQL 8.4 尚未单独实测。

## 常见问题

- **端口被占用**：脚本会核对 13307 上是否为本项目数据库；确认并停止冲突进程后重试，不要误停其他业务数据库。
- **Java 版本不支持**：检查 `JAVA_HOME`、`java -version` 和 `mvn -version`，确保实际使用 JDK 21 或以上。
- **测试无法连接数据库**：先用 `-DatabaseOnly` 启动专用实例，或配置独立的 `TEST_DB_*` 变量；库名必须是 `library_seat_test`。
- **prod 启动后无法登录**：该配置不自动创建用户，需要先初始化管理员账号。
- **重启后仍看到旧数据**：数据持久化是预期行为，重启不会重置演示库。
