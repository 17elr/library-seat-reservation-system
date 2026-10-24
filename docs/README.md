# 项目文档

[返回仓库首页](../README.md) · [界面图集](SHOWCASE.md) · [运行部署](deployment.md)

本目录对应 Vue 3 / Session / Redis 迁移版本。代码与文档通过 [PR #2](https://github.com/17elr/library-seat-reservation-system/pull/2) 审核。

## 想先运行项目

| 文档 | 可以找到什么 |
| --- | --- |
| [快速开始](../README.md#快速开始) | 环境准备、演示账号、启动入口 |
| [构建与部署](deployment.md) | Windows / Linux、端口、同源打包与配置 |
| [界面图集](SHOWCASE.md) | 学生与管理员的真实页面、交互说明 |
| [升级与回退](MIGRATION.md) | 备份、编号迁移、摘要对比、兼容回退 |

## 想看懂实现

| 文档 | 可以找到什么 |
| --- | --- |
| [架构与预约状态](architecture-overview.md) | 事务与统一 guard、规则版本、时间边界、缓存范围 |
| [API 清单](API.md) | 认证、预约动作、管理接口与错误状态 |
| [核心服务](../src/main/java/cn/edu/library/service/LibraryService.java) | 从业务写入口追踪实现 |
| [前端入口](../web/src/App.vue) | 页面组织、登录状态与组件 |

## 想核对验证与答辩材料

| 文档 | 可以找到什么 |
| --- | --- |
| [测试证据](TESTING.md) | 33 项后端、6 项浏览器、故障回源和迁移演练 |
| [后端用例清单](evidence/backend-test-summary.json) | 实际执行的测试名称与结果 |
| [超时 SQL 查询计划](evidence/expiry-explain.json) | 索引、估计扫描范围与排序的实际输出 |
| [面试说明](INTERVIEW.md) | 为什么保留统一锁、如何处理幂等与时间规则 |
| [变更记录](CHANGELOG.md) | 本轮迁移内容 |
| [参与维护](../CONTRIBUTING.md) | 反馈问题、分支和验证要求 |

## 历史设计资料

[指导阅读与约束](01-毕业设计指导阅读与约束.md)和[最初设计方案](02-图书馆座位预约管理系统设计方案.md)记录了旧版设计，保留作演进参考。当前行为以实现、架构与 API 文档为准。

私人论文、查重报告、数据库备份与包含学生身份信息的提交材料不上传本仓库。
