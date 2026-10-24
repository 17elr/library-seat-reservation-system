# API与返回约定

API前缀/api。JSON写请求带X-CSRF-TOKEN，同源fetch携带JSESSIONID。成功200/空响应；参数400、未认证401、无权/CSRF403、不存在404、业务冲突409、数据库暂不可用503。错误体message，字段校验增加errors对象。客户端不能指定可信userId/角色。

| 方法 | 路径（省略/api） | 权限/用途 |
|---|---|---|
| GET | /auth/csrf | 匿名，获取token |
| POST | /auth/login | 匿名+CSRF，account/password；轮换会话与token |
| GET | /auth/me | 当前Session用户，不返回密码哈希 |
| POST | /auth/logout | 已登录+CSRF，销毁会话 |
| GET | /bootstrap | 当前用户、资料、规则、公告、当前有效预约、serverTime |
| GET | /rooms/schedules?date= | 日期开放安排与serverTime |
| GET | /rooms/{id}/seats?start=&end= | 静态资料+实时占用，起止为上海本地ISO时间 |
| POST | /reservations | 学生；seatId/startTime/endTime/requestKey |
| GET | /me/reservations?status=&date=&page=&size= | 仅本人，size1—100，默认12 |
| GET | /reservations/{id} | 本人或管理员；预约/原规则/事件/serverTime |
| POST | /reservations/{id}/{action} | 本人；cancel/check-in/leave/return/check-out |
| GET | /me/violations?page= | 本人违约，默认12条 |
| POST | /me/password | oldPassword/newPassword；成功后重新登录 |
| GET | /admin/reservations?search=&status=&date=&page=&size= | 管理查询，总数与有效状态一致 |
| POST | /admin/reservations/{id}/close | reason |
| GET | /admin/violations?page= | 全部违约 |
| POST | /admin/violations/{id}/revoke | reason；重算限制 |
| GET/POST | /admin/users | 搜索（最多200条）/创建学生 |
| POST | /admin/users/{id}/enabled | enabled/reason，停用关闭有效预约 |
| POST | /admin/users/{id}/password | newPassword/reason，不取消预约 |
| GET/POST/PUT | /admin/rooms[/{id}] | 列表/新增/更新场地 |
| GET | /admin/rooms/{id}/seats | 座位资料 |
| POST/PUT | /admin/seats[/{id}] | 新增/更新，停用关闭相关预约 |
| GET/POST | /admin/openings | 特殊日期开放；roomId/specialDate/isClosed/openTime/closeTime/reason |
| GET/POST/PUT | /admin/notices[/{id}] | 公告维护 |
| GET/POST | /admin/rules | 规则列表（100条）/新增版本，不更新旧版本 |
| GET | /admin/statistics?start=&end= | 按预约开始日期，汇总预约/签到与当前使用数量 |
| GET | /admin/audits?page= | 审计分页 |

预约有效状态为RESERVED/CHECKED_IN/TEMP_LEFT；status=ACTIVE按计算有效状态筛选。detail/list增加persistedStatus/stateComputed；只读计算到期时必须提示后台待归档。API和图片缺失是真404，只有已知前端页面路径回index.html。

同key同seat/start/end返回原预约；同key不同参数409。同一次网络重试必须复用key，成功或主动改变选择才换key。sessionStorage仅保存当前用户待确认请求，注销清除，不存密码/可信角色。
