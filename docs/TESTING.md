# 验证证据（2026-10-24）

## 已执行

| 验证 | 结果 | 说明 |
|---|---|---|
| 原基线P0 | 19/19通过 | 真实MySQL8，迁移前先执行；业务语义保留 |
| 最终Maven verify | 33/33通过，0跳过 | 包含原16项业务+3项认证安全等价迁移，再新增14项；真实MySQL/Redis；包含打包 |
| 前端时间测试 | 1/1通过 | 上海日期跨年、半小时网格、非法serverTime不污染时钟 |
| Vue build | 通过 | JavaScript/SFC/Element Plus/fetch，同源jar打包 |
| 格式检查 | Vue/JS及Java均通过 | prettier与prettier-plugin-java，版本锁定 |
| npm audit | 0 vulnerabilities | 本次锁文件；不代表永久安全 |
| Playwright | 6/6通过 | 本机Edge，1440×1000，真实已打包应用 |
| 原库恢复与升级 | 12表原列摘要一致 | 原库备份21用户/3房间/120座位，原库预约历史为0，另做非空历史演练 |
| 升级/回退/再次升级 | 通过 | 旧版tag的实际jar；非空样例及升级后新增历史均保留 |

本地JDK23以release21编译，MySQL8.0.44、原生Redis5.0.14。未安装Docker，未在本机运行Compose；Actions配置Java21、MySQL8、Redis7.4和Chromium，用CI补足目标环境差异。CI结果以PR检查为准，不能用本地结果冒充远程通过。

## 33项后端覆盖

原语义包括完整签到/暂离/返回/签退与使用区间、20人同座位竞争、同人跨房间竞争、重复key/异参数、相邻/重叠时段、日期/网格、签到与暂离相等边界、规则版本、场地维护、违约撤销重算封禁、事务回滚、详情归属、特殊开放、本人范围与分页、安全登录/CSRF/密码重置不取消预约。

新增包括GET无mapper写调用/无guard与计算状态、写入补处理相关到期预约、第三次违约不能绕过限制、405条错序截止记录分批、过期与签到并发幂等、登录轮换session/token与伪造身份头、分页超限/缺失参数/资源404、改密和停用失效、真实Redis命中和提交后失效/事务回滚、缓存旧启用值不能越过数据库、真实断开端口回源、缓存未来规则到时生效、事件插入故障全回滚、编码/分号管理路径授权，以及管理维护不重分类已过期预约。

定时任务初始延迟跟随配置的sweep-delay；测试设为1小时，避免30秒后台任务进入批次断言。并发用例显式调用sweep验证竞争，生产默认仍30秒。

## 浏览器6项

1. 登录配图、键盘Enter选座、成功预约、详情倒计时、取消与终态。
2. 快速切换第二/第三阅览室并延迟旧响应，最终仍为第三室座位。
3. 第一次网络中断、第二次409；两次请求payload和requestKey完全相同，失败保留选择，冲突刷新后要求重新选座。
4. 401返回明确的重新登录提示。
5. 管理页面直接刷新、必填字段汇总焦点、503失败保留填写内容。
6. 实际停用/恢复座位、闭馆/恢复开放、创建学生/重置密码/停用、发布公告、新增规则、审计查询。

图像来自真实浏览器；[旧版选座](images/seat-reservation.png)与[Vue选座](images/vue/booking.png)可对照。未用图片假装运行结果。测试产生的演示历史保留在迁移副本，未删除原库历史。

## SQL与缓存的实际观察

[EXPLAIN JSON](evidence/expiry-explain.json)来自405条到期样例。签到分支使用idx_expiry_checkin，access_type=range，使用status/checkin_deadline，覆盖索引，rows_examined_per_scan估计405，带filesort；外层每批只给Java最多200个id。该样例3个处理批次+1次空查询，全部处理且重复扫描不新增违约。不能将估计扫描行数写成实测耗时，也不能宣称无排序或恒定复杂度。

mapper spy验证bootstrap/seats/detail/list/violations/statistics没有lockWrites、INSERT/UPDATE、usage或block写调用。缓存测试在真实Redis中冷读/热读两次rooms，MySQL mapper.rooms调用从2次降为1次，TTL落在119—150秒（包含执行耗时），事务回滚保留key，提交才删除。失效/故障日志有记录，断开连接真实访问回源仍能预约。

没有实施生产压力测试、QPS承诺、Redis分布式锁或限流。单实例Session、统一guard写瓶颈和缓存短期旧展示是当前明确边界。前端整包约1MB（gzip约343KB），尚未按页面拆分加载。

## 非空数据演练

独立库library_seat_rehearsal_20261024145542：升级前12表均有数据，2条预约、1段usage、3事件、1违约、1审计、1特殊日期安排、2版规则。V1回填全部等于原rule_id的签到宽限；原列/ID/哈希/时间摘要不变。升级后通过API新增并取消1条预约，导出最新库，运行legacy/library-seat-20261024构建的旧jar，再运行新版，两次原列摘要均一致。私有SQL/摘要与日志位于.local，不上传GitHub。

另行验证测试库误配：TEST_DB_URL指向非library_seat_test时，在DataSource/Flyway启动前拒绝上下文；正确测试库URL正常执行安全用例。原测试中的库名断言仍保留，双重防止误清表。
