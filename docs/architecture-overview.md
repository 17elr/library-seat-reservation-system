# 当前系统架构

```mermaid
flowchart LR
    U[学生 / 管理员 PC 浏览器] --> V[Thymeleaf / 原生 JavaScript]
    V --> A[Spring Security / Session / CSRF]
    A --> C[Web 控制器]
    C --> S[LibraryService / 预约规则与状态]
    T[定时扫描 / 启动补偿] --> S
    S --> M[MyBatis / 事务 / 统一写锁]
    M --> DB[(MySQL / 规则版本 / 事件与审计)]
```

图中节点对应当前仓库代码；虚线表示可选链路，不代表已完成外部生产验收。具体取舍见[负责模块与技术决策](PORTFOLIO.md)。
