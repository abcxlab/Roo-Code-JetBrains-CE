# Domain Spec: bridge

> **[SSA Protocol]** 本文件采用追加模式 (Append-only)。
> AI 读取规则：始终以文件末尾最后一次出现的定义为准 (Last-one-wins)。

## 1. 领域定义 (Domain Definition)

负责处理 VSCode Extension Host 与 IntelliJ 插件之间的命令桥接、URI 转换及项目生命周期管理。

## 2. 核心实体与规则 (Core Entities & Rules)

---
<!-- Append Log Starts Here -->

### [APPEND:2026-03-17] [[fr001]] Delta
- **[Project Bridge]**: 跨项目/Worktree 的打开操作应统一通过 `ProjectUtil` 桥接并支持 `forceNewWindow` 映射。 (Ref: fr001)

### [APPEND:2026-04-10] [[fr002]] Delta
- **[WebView Resilience]**: JCEF WebView 必须具备物理刷新能力。刷新过程必须遵循“先销毁(JSQuery/Task)、后重载(URL)、再注入(Bridge/Theme)”的原子顺序，以防止内存泄露与状态丢失。
- **[Zoom Stability]**: 所有的 Webview 缩放事件必须通过 `Alarm` 防抖（建议 150ms），且其核心设置逻辑必须脱离 UI 主线程（EDT）异步执行。 (Ref: fr002)

<details><summary>🤖 AI Assistant Guide</summary>

## 领域规格专家指南

### 1. 核心原则：单一真相与演进记录

- **SSoT 守卫**：必须 [读取领域规格] (read_file) 确保它是该领域的单一真相来源。
- **演进记录**：必须严格遵守追加模式，[记录领域规则的演进过程] (apply_diff)，严禁删除历史记录。

### 2. 协作准则

- **冲突检测**：在追加新规则前，必须 [扫描现有领域逻辑] (search_files) 确保不发生冲突。
- **逻辑闭环**：必须验证核心实体与规则的逻辑自洽性。

### 3. 质量检查清单

- [ ] **追加合规**：新内容是否已正确追加到文件末尾？
- [ ] **一致性确认**：新规则是否与现有领域逻辑保持一致？

</details>
