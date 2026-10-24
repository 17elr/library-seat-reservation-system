# 升级与回退手册

基线bbdca97eca5004f859101cb129fbc55d80353134，旧版tag为legacy/library-seat-20261024。功能分支feature/vue-reservation-cache，通过PR审核，不强推、不自动合并主分支。

## 升级

1. 停止应用写入与调度，保留原数据目录。在独立MySQL备份原库：`python scripts/db-tool.py backup --port 13318 --database library_seat`。连接取DB_USER/DB_PASSWORD，MYSQL_BIN可指定客户端路径；默认输出.local/backups，忽略Git。
2. 工具同时生成同名JSON：12张业务表的原列、行数、按ID排序的逐行JSON摘要。备份与校验快照要求停止写入；mysqldump本身使用single-transaction。
3. 在新建空库恢复副本：`python scripts/db-tool.py restore-copy --database library_seat_rehearsal_copy --file BACKUP.sql`。该操作拒绝非空库，必须由DBA先创建空库/授权。
4. 将DB_URL显式指向副本，默认不开demo，启动新版。初始化SQL仅CREATE IF NOT EXISTS/INSERT IGNORE guard，Flyway以0为基线执行V1；新增auth_version和checkin_deadline，并按每条原rule_id回填；增加超时索引。
5. 在没有生命周期写入的情况下执行`python scripts/db-tool.py compare --database library_seat_rehearsal_copy --file BACKUP.json`。新列排除，原列（包括主键、外键、密码哈希、事件时间）必须完全一致。核对deadline与原规则，检查状态边界与本人的历史查询。
6. 副本验证后再对目标库执行同样的停写、备份、迁移与验收。MySQL DDL会自动提交，失败不能假定整次DDL已回滚；保留失败副本与日志，先在新的空副本诊断，不直接repair或覆盖原库。

原库若有活跃到期预约，启动补偿会按规则改变状态/事件；在核对数据时应先单独记录迁移SQL结果，再解释合法的生命周期增量。不能把这些增量当作任意丢失记录。

## 回退

`./scripts/rollback-code.ps1`先导出当前库（包括本轮新增预约/事件），备份失败立即停止。停止新版，另建旧版tag工作目录，构建旧版并显式指定相同DB_URL/USER/PASSWORD，禁用demo。

V1仅新增兼容列和索引，旧版mapper可以忽略，回退保留这些字段和Flyway历史，不运行破坏性的DROP COLUMN。不得直接恢复升级前备份覆盖现有库，否则会丢掉新增历史。旧版GET又会执行扫描，应在停写/合适的样例状态下对比摘要。恢复新版时再次校验Flyway历史。

旧版工作目录示例：`git worktree add ../library-seat-legacy legacy/library-seat-20261024`。旧版本地profile使用旧端口，务必覆盖DB_URL，并使用prod profile、library.seed-demo=false。别把开发测试指向真实使用库。

## 可重复的独立演练

```powershell
$env:DB_USER='root' # 仅本地专用MySQL实例，需要CREATE DATABASE权限
python scripts/rehearse-migration.py --dump OLD_BACKUP.sql --legacy-jar LEGACY_TAG_BUILT.jar
```

工具创建带时间戳的新库，拒绝覆盖非空库；加入原规则不同的历史预约、usage、事件、违约、审计和特殊日期安排，比较升级摘要/原规则回填，再通过新版API新增并取消预约；导出最新历史，实际运行旧版、再运行新版并两次比较摘要。临时应用使用动态空闲端口，演练库/备份保留供检查，脚本不自动删除任何库。

测试副本含演示账号，不能使用含真实个人资料的备份上传GitHub；仅公开去标识的数量与查询计划。
