# 变更记录

## 2026-10-24 Vue / Session / Redis迁移

保留PC界面蓝色系、图片和业务API，迁移Vue3 SFC/Composition API/JavaScript/Element Plus/fetch。登录改为可解释的Session/CSRF/authVersion并保留BCrypt；移除Thymeleaf和完整Spring Security认证依赖。

GET不再持锁扫描；纯函数/SQL显示有效状态，200条候选分批定时与启动恢复；book处理相关超时并重新校验。Redis仅静态资料，提交后失效和故障回源。新增V1迁移、备份摘要对比与兼容代码回退演练。

保留原19项测试语义，扩充到33项真实MySQL集成测试；新增前端时间测试和关键浏览器回归、CI、截图、API/迁移/面试说明。具体执行结果与环境限制见TESTING。
