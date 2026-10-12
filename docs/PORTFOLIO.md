# 负责模块与关键技术决策

## 个人负责范围

本人主导需求分析、预约规则、数据库设计、后端与页面实现、测试、部署脚本及项目文档，使用 AI 辅助编码与排错；贡献方式经本人确认。

| 负责模块 | 代码入口 | 讲解重点 |
| --- | --- | --- |
| 预约生命周期 | [service](../src/main/java/cn/edu/library/service/) | 预约、签到、暂离、返回、签退与异常关闭 |
| 场地与规则管理 | [web](../src/main/java/cn/edu/library/web/)、[model](../src/main/java/cn/edu/library/model/) | 开放时间、规则版本、公告、预约审计 |
| 数据一致性 | [mapper](../src/main/resources/mapper/)、[schema](../src/main/resources/db/schema.sql) | 请求幂等、重叠区间、统一写锁、事务 |
| 认证与补偿 | [config](../src/main/java/cn/edu/library/config/) | Spring Security、CSRF、后台超时扫描、启动补偿 |
| 测试与交付 | [test](../src/test/)、[scripts](../scripts/) | 19 项集成测试、独立测试库、Windows 启停与 CI |

## 技术决策

| 决策 | 原因与代价 |
| --- | --- |
| 单体应用 + Thymeleaf | 以一个 JAR 交付学生端与管理端；当前 PC 优先，未做小程序。 |
| write_guard 统一行锁 | 使跨座位、跨用户操作易于保证一致；会串行化写入，20 人竞争测试不等于高吞吐能力。 |
| 请求标识 + 同事务事件记录 | 重试避免新增重复预约，状态与事件一起提交。 |
| 预约引用规则版本 | 管理员修改规则后，历史预约仍可解释。 |
| 后台扫描 + 启动补偿 | 服务重启后仍处理已超时预约；前端倒计时只显示剩余时间。 |
| 不宣称真实到馆率或座位利用率 | 按钮签到不能证明到馆，现有数据不足以复原历史可用时长。 |

## 证据与简历表述

[现有演示截图](images/)、[架构与测试](architecture.md)、[CI](../.github/workflows/ci.yml)均随仓库保留。

主导 Spring Boot 图书馆预约系统开发，实现预约、签到、暂离、超时释放与审计流程；通过请求幂等、统一数据库行锁和事务控制避免重复占用，并用规则版本与事件记录追踪历史；19 项 MySQL 集成测试覆盖并发抢座、时间边界、权限和事务回滚。

测试数量来自现有仓库，复现需使用专用 library_seat_test；未进行生产规模负载测试。
