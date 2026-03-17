# VSCode IPC 契约详解

**本文档的目标是全面、准确地解释本项目中所有源自 VSCode 的 IPC 服务契约。这些契约的 Kotlin 接口定义于 `jetbrains_plugin/src/main/kotlin/com/roocode/jetbrains/actors/` 目录下。**

## 1. 核心概念

本项目通过一套双向 **RPC (远程过程调用)** 机制来模拟 VSCode 主进程与插件进程 (`extension_host`) 之间的通信。这套机制完全遵循 VSCode 的原生设计。

**注**: RPC 是 **IPC (进程间通信)** 的一种高级抽象。IPC 是一个广义概念，指代任何进程间的通信方式（如 Sockets、管道）。而 RPC 是一种具体的编程模型，它利用 IPC 机制，使得调用远程进程的函数就像调用本地函数一样简单。因此，我们可以说本项目**使用 Sockets (一种 IPC 方式) 来实现了一套 RPC 通信模型**。

-   **`MainContext`**: 定义了由 **JetBrains 插件 (主进程)** 实现、供 **`extension_host` (插件进程)** 调用的服务。
    -   **方向**: `extension_host` -> `jetbrains_plugin`
    -   **作用**: 允许插件请求 IDE 的核心能力，如文件操作、UI 显示、编辑器控制等。

-   **`ExtHostContext`**: 定义了由 **`extension_host` (插件进程)** 实现、供 **JetBrains 插件 (主进程)** 调用的服务。
    -   **方向**: `jetbrains_plugin` -> `extension_host`
    -   **作用**: 允许 IDE 通知插件状态变化、传递事件、或请求插件侧提供数据。

---

## 2. 核心编程契约 (Core Programming Contracts)

为了确保 `jetbrains_plugin` (Kotlin/JVM) 与 `extension_host` (TypeScript/Node.js) 之间 RPC 通信的稳定性、可维护性和可预测性，所有开发者（包括 AI 编程助手）在扩展或修改 IPC 功能时，**必须**遵循以下核心契约。这些契约源于实践中的问题，旨在从根本上杜绝特定类型的错误。

### 2.1 RPC 接口命名规范 (RPC Interface Naming Convention)

- **问题来源**: 在调试“问题诊断”功能时，出现 `invokeHandler error: Unknown method $$acceptMarkersChange` 的错误。
- **根本原因**: `RPCProtocol.kt` 的实现会自动为所有调用的方法名添加 `$` 前缀，而当时的 `ExtHostDiagnosticsProxy` 接口定义中错误地手动包含了 `$`，导致最终方法名被污染为 `$$acceptMarkersChange`。
- **规范**:
    1.  **【强制】** 所有在 Kotlin 中定义的 RPC 代理接口（无论是 `MainThread...Shape` 还是 `ExtHost...Proxy`），其方法名**必须**使用标准的驼峰式命名（`camelCase`），且**严禁**包含任何手动添加的前缀，特别是 `$` 符号。
    2.  **【认知】** RPC 框架是**唯一**负责在运行时动态添加 `$` 前缀的组件。开发者应信赖此机制，无需手动干预。
    3.  **【参考】** 在定义新的 RPC 接口时，应参考代码库中其他工作正常的接口（如 `ExtHostDocumentsProxy`）作为命名和结构的范本。

    **示例**:
    ```kotlin
    // 错误范例 (会导致 '$$' 问题)
    interface ExtHostDiagnosticsProxyWrong {
        fun `$acceptMarkersChange`(markers: List<Any>) // 错误：不应手动添加 `$`
    }

    // 正确范例
    interface ExtHostDiagnosticsProxyCorrect {
        fun acceptMarkersChange(markers: List<Any>) // 正确：标准的驼峰命名
    }
    ```

### 2.2 跨语言数据结构转换协议 (Cross-Language Data Structure Protocol)

- **问题来源**: 在修复了 `$$` 问题后，`extension_host` 抛出 `TypeError: .for is not iterable` 的错误。
- **根本原因**: Kotlin 的 `List<Pair<A, B>>` 类型在通过 Gson 序列化后，其 JSON 格式为对象数组 `[{ "first": ..., "second": ... }]`，而 TypeScript/JavaScript 的 `for...of` 解构语法 `for (const [a, b] of data)` 期望接收的是数组的数组 `[[...], [...]]`。
- **规范**:
    1.  **【强制】** 在 RPC 接口的方法签名中，**严禁**直接使用特定于语言的、序列化行为不明确的复杂类型，如 Kotlin 的 `Pair`、`Triple` 等。
    2.  **【强制】** 必须优先使用两种语言生态中最基础、最通用的集合类型作为数据传输对象（DTO），包括：
        *   `List` (对应 JS 的 `Array`)
        *   `Map<String, Any?>` (对应 JS 的 `Object`)
        *   `String`, `Int`, `Boolean`, `Double`, `null`
    3.  **【强制】** 当需要传递“元组”（Tuple）或键值对列表时，**必须**使用 `List<Any>` 来模拟。例如，一个 `(URI, List<Problem>)` 的元组应表示为 `listOf(uri, problemList)`。
    4.  **【原则】转换边界原则**: 数据结构的转换应在离 RPC 调用最近的边界完成，以保持核心业务逻辑的清晰。
        *   **调用 `ExtHost` 时**: 在 `ExtensionHostManager` 中，将业务数据模型（如 `Map`）转换为符合协议的 `List<List<Any>>`。
        *   **实现 `MainThread` 时**: 在 `MainThread...Shape` 的实现类中，将接收到的基础类型（如 `List<Any>`）转换回 Kotlin 的业务对象。

    **示例**:
    ```kotlin
    // 原始业务数据 (Kotlin)
    val problems: Map<URI, List<Problem>> = // ...

    // --- 在 ExtensionHostManager 中进行转换 ---

    // 错误的数据准备方式
    val markersAsListOfPairs = problems.map { (uri, list) -> Pair(uri, list) }
    // proxy.acceptMarkersChange(markersAsListOfPairs) // 错误：会产生不可迭代的对象数组

    // 正确的数据准备方式
    val markersAsListOfLists = problems.map { (uri, list) -> listOf(uri, list) }
    proxy.acceptMarkersChange(markersAsListOfLists) // 正确：将生成 JSON [[...], [...]]

    // --- RPC 接口定义 ---
    interface ExtHostDiagnosticsProxy {
        // fun acceptMarkersChange(markers: List<Pair<URI, List<Problem>>>) // 错误
        fun acceptMarkersChange(markers: List<List<Any>>) // 正确
    }
    ```

---

## 3. `MainContext` 契约详解 (extension_host -> jetbrains_plugin)

### 3.1 文档与编辑器 (Documents & Editors)

#### `MainThreadBulkEdits`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 原子性地执行一系列涉及到多个文件的修改操作（Workspace Edit）。这是实现 "AI 一键修改代码"、"重构" 等功能的基石。
-   **关键方法**:
    -   `$tryApplyWorkspaceEdit(workspaceEditDto)`: 接收一个包含文件创建、删除、重命名和文本修改的复杂编辑对象。
-   **实现原理**:
    -   **文件操作**: 直接使用 `java.nio.file.Files` 和 `java.io.File` API 执行文件的创建、移动（重命名）、删除。操作后会通过 `LocalFileSystem.getInstance().refreshIoFiles` 刷新 IntelliJ 的虚拟文件系统（VFS）以同步状态。
    -   **文本编辑**: 遍历所有文本修改请求，通过 `EditorAndDocManager` 获取对应文档的 `EditorHolder`，并调用其 `applyEdit` 方法，最终在 IntelliJ 的 `WriteCommandAction` 中修改 `Document` 对象。
-   **典型场景**: AI 编码助手生成了一个涉及修改 3 个文件、新建 1 个文件的代码片段，通过一次调用此服务来完成所有操作。

#### `MainThreadDocuments`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 管理文本文档的生命周期，如创建、打开和保存。
-   **关键方法**:
    -   `$tryCreateDocument()`: 请求主进程创建一个新的无标题文件。
    -   `$tryOpenDocument(uri)`: 请求主进程打开一个指定 URI 的文档。如果文件不存在，会尝试创建它。
    -   `$trySaveDocument(uri)`: 请求主进程保存一个已打开的文档。
-   **实现原理**:
    -   所有操作都委托给 `EditorAndDocManager` 服务来处理。
    -   此类还包含一个重要的**反向同步逻辑**：它通过 `FileDocumentManagerListener` 监听 IntelliJ 内部的文档保存事件。当用户在 IDE 中手动保存一个被 `extension_host` 所管理的文档时，它会通过 `DocumentSyncService` 将保存状态同步回 `extension_host`。
-   **典型场景**: 用户在 Webview 中点击“新建文件”按钮，`extension_host` 调用 `$tryCreateDocument` 来让 IntelliJ 创建一个新标签页。

#### `MainThreadDocumentContentProviders`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 为自定义 URI scheme 提供文本内容。这允许插件创建“虚拟文档”。
-   **关键方法**:
    -   `$registerTextContentProvider(scheme)`: 注册一个能够为特定 scheme (例如 `readonly-doc://` ) 提供内容的提供者。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadDocumentContentProviders` 仅打印日志。
-   **典型场景**: 一个 Git 插件想要显示某个历史版本的只读文件内容，它可以提供一个 `git-commit://<hash>/path/to/file` 的 URI，并通过此服务注册一个 `git-commit` scheme 的内容提供者。

#### `MainThreadTextEditors`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 控制单个文本编辑器实例的外观和行为。
-   **关键方法**:
    -   `$tryShowTextDocument(resource)`: 确保一个文档在编辑器区域可见，如果尚未打开则打开它。
    -   `$tryRevealRange(id, range)`: 在指定的编辑器中滚动，以确保给定的代码范围可见。
    -   `$trySetDecorations(id, key, ranges)`: 在编辑器中应用文本高亮、行尾注释等装饰。
    -   `$tryApplyEdits(id, edits)`: 在单个编辑器中执行文本编辑操作。
-   **实现原理**: 大部分功能都通过 `EditorAndDocManager` 找到对应的 `EditorHolder` 实例，然后调用其封装好的 IntelliJ `Editor` API 来实现。
-   **典型场景**: 在代码审查视图中，当用户点击一条评论时，`extension_host` 调用 `$tryShowTextDocument` 打开相应文件，并调用 `$tryRevealRange` 定位到评论所在行。

#### `MainThreadEditorTabs`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 管理编辑器标签页（Tabs），如关闭和移动。
-   **关键方法**:
    -   `$closeTab(tabIds)`: 关闭一个或多个指定的标签页。
-   **实现原理**: 通过 `EditorAndDocManager` 找到对应的标签页并执行关闭操作。
-   **典型场景**: 插件在完成某项任务后，需要自动关闭它之前打开的临时文件标签页。

#### `MainThreadEditorInsets`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 在文本编辑器内部的某一行下方嵌入一个 Webview 视图。
-   **关键方法**:
    -   `$createEditorInset(id, uri, line, height, options)`: 在指定文档的指定行创建一个内联 Webview。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: 一个 AI 编码插件在代码行下方显示一个内联的 diff 视图，让用户可以逐行接受或拒绝代码建议。

### 2.2 工作区与文件系统 (Workspace & Filesystem)

#### `MainThreadWorkspace`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 提供工作区级别的操作和信息，例如管理文件夹、文件搜索、保存工作区状态等。
-   **关键方法**:
    -   `$updateWorkspaceFolders(...)`: 允许插件动态地添加或移除项目中的根文件夹。
    -   `$startFileSearch(...)`: 执行文件搜索。
    -   `$saveAll()`: 保存所有打开的“脏”文件。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未专门注册此服务，其部分功能由其他服务（如 `MainThreadFileSystem`）承载。
-   **典型场景**: 一个项目管理插件，允许用户在不关闭 IntelliJ 项目的情况下，动态添加一个新的代码目录到当前工作区进行分析。

#### `MainThreadFileSystem`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 为 `extension_host` 提供底层的、原始的文件系统操作 API。插件通过此服务可以像操作本地文件一样读、写、删除、重命名文件和文件夹。
-   **关键方法**:
    -   `$stat(uri)`: 获取文件或目录的元数据（大小、创建/修改时间、类型等）。
    -   `$readFile(uri)`: 读取文件内容并返回字节数组。
    -   `$writeFile(uri, content)`: 将字节数组写入文件。
    -   `$delete(uri, options)`: 删除文件或文件夹，支持递归删除。
    -   `$rename(source, target)`: 重命名或移动文件/文件夹。
-   **实现原理**: 直接使用 `java.io.File` 和 `java.nio.file.Files` API 来执行实际的文件系统操作。它是一个非常直接的到本地文件系统的桥梁。
-   **典型场景**: 插件需要读取一个非项目内的配置文件（例如 `~/.mytool/config.json`），它会通过此服务来读取文件内容。

#### `MainThreadFileSystemEventService`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 管理文件系统监听器 (File Watcher)。
-   **关键方法**:
    -   `$watch(extensionId, session, resource, opts)`: 请求主进程开始监听指定文件或目录的变化。
    -   `$unwatch(session)`: 停止监听。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadFileSystemEventService` 仅打印日志。
-   **典型场景**: 一个自动同步插件需要监听某个目录下的文件变化，以便在文件被修改时自动上传到服务器。

#### `MainThreadConfiguration`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 允许 `extension_host` 修改配置项。
-   **关键方法**:
    -   `$updateConfigurationOption(target, key, value)`: 更新一个配置项的值。
    -   `$removeConfigurationOption(target, key)`: 移除一个配置项。
-   **实现原理**: 使用 IntelliJ 的 `PropertiesComponent` 来持久化存储配置。它能够区分不同的配置范围（`ConfigurationTarget`），如 `APPLICATION` (全局)、`WORKSPACE` (项目级) 和 `USER` (用户级)，并将配置项存储在对应的位置。
-   **典型场景**: 插件提供了一个设置页面，用户修改了某个设置后，`extension_host` 调用此服务将新值保存到项目级（`WORKSPACE`）的配置中。

#### `MainThreadSearch`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 允许 `extension_host` 注册自定义的搜索提供者。
-   **关键方法**:
    -   `$registerFileSearchProvider(scheme)`: 注册一个能对特定 scheme 提供文件搜索能力的服务。
    -   `$registerTextSearchProvider(scheme)`: 注册一个能对特定 scheme 提供文本内容搜索能力的服务。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadSearch` 仅打印日志。
-   **典型场景**: 一个连接到远程服务器的插件，注册了一个 `remote-fs://` 的 scheme。当用户搜索时，主进程可以委托该插件在远程服务器上执行搜索。

### 2.3 用户界面与交互 (UI & Interaction)

#### `MainThreadCommands`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 注册和执行命令。这是 VSCode 插件模型的核心，允许插件将功能暴露为可被用户（通过命令面板）、快捷键或其它插件调用的“命令”。
-   **关键方法**:
    -   `$registerCommand(id)`: `extension_host` 通知主进程，它有一个 ID 为 `id` 的命令可以执行。
    -   `$executeCommand(id, args)`: 请求主进程执行一个命令。
-   **实现原理**: 维护一个 `CommandRegistry`。当 `$executeCommand` 被调用时，它会在注册表中查找对应的 `ICommand` 对象，并调用其 `handler` 的 `execute` 方法。本项目已经预注册了一些内部命令，如用于编辑器的 `roocode.editor.open`。
-   **典型场景**: 用户在命令面板（`Ctrl+Shift+P`）中选择并运行了一个由插件定义的命令。

#### `MainThreadClipboard`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 提供对系统剪贴板的读写能力。
-   **关键方法**:
    -   `$readText()`: 读取剪贴板中的文本内容。
    -   `$writeText(value)`: 将文本内容写入剪贴板。
-   **实现原理**: 使用 Java AWT 的 `Toolkit.getDefaultToolkit().getSystemClipboard()` 来与操作系统的剪贴板进行交互，这是一个非常直接的桥接。
-   **典型场景**: 插件提供一个“复制错误信息”的按钮，点击后调用 `$writeText` 将日志内容放入用户剪贴板。

#### `MainThreadDialogs`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 显示原生的文件打开/保存对话框。
-   **关键方法**:
    -   `$showOpenDialog(options)`: 显示一个文件选择对话框。
    -   `$showSaveDialog(options)`: 显示一个文件保存对话框。
-   **实现原理**: 使用 IntelliJ 的 `FileChooser` 和 `FileChooserFactory` API 在主 UI 线程中弹出原生对话框。这是一个异步操作，通过 `suspendCancellableCoroutine` 挂起等待用户操作完成。
-   **典型场景**: 插件需要用户选择一个本地文件进行上传，它会调用 `$showOpenDialog` 来实现。

#### `MainThreadMessageService`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 向用户显示信息、警告、错误等通知。
-   **关键方法**:
    -   `$showMessage(severity, message, options, commands)`: 显示一个消息。
-   **实现原理**: 根据 `options` 中的 `modal` 参数决定行为：
    -   **非模态 (modal: false)**: 使用 IntelliJ 的 `NotificationGroupManager` 显示一个右下角的瞬时通知（Toast）。
    -   **模态 (modal: true)**: 使用 `Messages.showDialog` 显示一个会阻塞用户操作的对话框，并可以自定义按钮。
-   **典型场景**: 插件完成一个长时间任务后，调用 `$showMessage` 显示一个“任务已完成”的通知。或者在执行关键操作前，显示一个模态对话框让用户确认。

#### `MainThreadQuickOpen`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 提供 VSCode 风格的快速选择（Quick Pick）和输入框（Input Box）UI。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: 插件需要向用户呈现一个可搜索的选项列表（例如，选择一个要激活的环境），就会使用 Quick Pick 功能。

#### `MainThreadStatusBar`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 在 IDE 的状态栏中添加或移除自定义条目。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: 一个代码质量检查插件，在状态栏实时显示当前文件的警告和错误数量。

#### `MainThreadTreeViews`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 管理在侧边栏等位置显示的树状视图。
-   **关键方法**:
    -   `$registerTreeViewDataProvider(treeViewId)`: 注册一个树视图，主进程知道了这个视图的存在。
    -   `$refresh(treeViewId)`: 通知主进程刷新整个树或特定节点的数据。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: GitLens 插件在侧边栏显示一个复杂的树视图，包含代码仓库的分支、提交记录、文件历史等。

#### `MainThreadUrls` & `MainThreadUriOpeners`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: `MainThreadUrls` 用于处理自定义协议（如 `vscode://...`），而 `MainThreadUriOpeners` 允许插件成为特定类型 URI（如 `http` 链接）的默认打开器。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadUrls` 仅打印日志。
-   **典型场景**: 一个代码协作插件可以注册一个 `codetogether://join?session=123` 的 URI 处理器，当用户在浏览器中点击这个链接时，可以自动唤起 IDE 并加入协作会话。

#### `MainThreadWindow`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 提供对 IDE 主窗口状态的访问和操作。
-   **关键方法**:
    -   `$getInitialState()`: 获取窗口当前是否激活、是否拥有焦点。
    -   `$openUri(uri)`: 请求系统使用默认程序打开一个外部链接（如网页）。
-   **实现原理**: 使用 IntelliJ 的 `WindowManager` 获取窗口状态，使用 Java AWT 的 `Desktop.browse()` 打开外部链接。
-   **典型场景**: 插件中的“查看文档”链接，点击后调用 `$openUri` 在用户的默认浏览器中打开相关网页。

#### `MainThreadWebviews`, `MainThreadWebviewPanels`, `MainThreadWebviewViews`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 这三个服务共同管理 Webview 的生命周期和通信。
    -   `MainThreadWebviews`: 负责基础通信，如设置 HTML 内容 (`$setHtml`) 和从 `extension_host` 向 Webview 发送消息 (`$postMessage`)。
    -   `MainThreadWebviewPanels`: 管理作为编辑器标签页打开的 Webview（例如，一个 Markdown 预览页）。
    -   `MainThreadWebviewViews`: 管理作为侧边栏等视图一部分的 Webview（例如，Roo Code 的主聊天界面）。
-   **实现原理**: 这是本项目的核心之一。当 `extension_host` 请求创建一个 Webview 时，这些服务会协同工作，最终通过 `WebViewManager` 创建一个 `JBCefBrowser` 实例来承载 Web内容。`$setHtml` 的内容会被注入到这个浏览器实例中，而 `$postMessage` 的消息会通过执行 JavaScript 发送给 Webview 内部的 `window.postMessage`。
-   **典型场景**: Roo Code 插件启动时，通过 `MainThreadWebviewViews.$registerWebviewViewProvider` 注册侧边栏视图，然后通过 `MainThreadWebviews.$setHtml` 将前端应用的 HTML 设置进去。

### 2.4 终端、调试与任务 (Terminal, Debug & Tasks)

#### `MainThreadTerminalService`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 管理集成终端的生命周期。
-   **关键方法**:
    -   `$createTerminal(extHostTerminalId, config)`: 请求主进程创建一个新的终端实例。
    -   `$sendText(id, text, shouldExecute)`: 向指定的终端发送文本（模拟用户输入）。
    -   `$dispose(id)`: 关闭并销毁一个终端实例。
    -   `$show(id)`: 显示并聚焦到指定的终端面板。
-   **实现原理**: 使用一个 `TerminalInstanceManager` 来管理所有的 `TerminalInstance` 对象。每个 `TerminalInstance` 内部会创建一个 `PtyProcess` (伪终端)，这是与底层 shell（如 bash, zsh）交互的桥梁。所有对终端的操作最终都委托给对应的 `TerminalInstance` 来执行。
-   **典型场景**: 插件需要执行一个长时间的 shell 命令，它会先调用 `$createTerminal` 创建一个隐藏的终端，然后通过 `$sendText` 发送命令，并通过 `ExtHostTerminalService` 监听其输出。

#### `MainThreadTerminalShellIntegration`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 配合 Shell Integration 功能，允许插件更智能地与 shell 交互。
-   **关键方法**:
    -   `$executeCommand(terminalId, commandLine)`: 在指定终端中执行一条命令，并利用 shell integration 的能力来追踪命令的开始、结束和输出。
-   **实现原理**: 此服务找到对应的 `TerminalInstance` 并调用 `sendText` 来执行命令。它的特殊之处在于，它依赖于 shell 脚本（注入到 shell 配置文件中）发出的特殊序列号来精确地识别命令的边界和状态。
-   **典型场景**: VSCode 的 "Run Task" 功能，可以在终端中执行一个构建命令，并通过 shell integration 准确地知道这个命令何时执行完毕，以及其退出码是什么。

#### `MainThreadDebugService`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 充当 `extension_host` 中的调试插件与 IntelliJ 平台调试功能之间的桥梁。
-   **关键方法**:
    -   `$registerDebugConfigurationProvider(...)`: 注册一个调试配置提供者，允许插件动态提供或修改启动调试所需（如 `launch.json`）的配置。
    -   `$startDebugging(...)`: 启动一个调试会话。
    -   `$stopDebugging(...)`: 停止一个调试会话。
    -   `$acceptDAMessage(...)`: 从 `extension_host` 接收一个 DAP (Debug Adapter Protocol) 消息，并将其转发给 IntelliJ 正在运行的调试器。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadDebugService` 仅打印日志。
-   **典型场景**: 用户在 `launch.json` 中配置了一个 "Launch Node.js" 的调试项并点击启动。`extension_host` 中的 Node.js 调试插件会通过 `$startDebugging` 请求主进程启动调试，之后所有调试过程中的通信（如设置断点、单步执行）都通过 DAP 消息进行。

#### `MainThreadTask`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 管理和执行 VSCode 的“任务”（Tasks），这些任务通常是外部脚本或构建命令（如 `npm run build`）。
-   **关键方法**:
    -   `$registerTaskProvider(...)`: 允许插件动态地提供任务列表。
    -   `$executeTask(...)`: 执行一个指定的任务。
    -   `$terminateTask(id)`: 终止一个正在运行任务。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadTask` 仅打印日志。
-   **典型场景**: 用户打开一个 `Makefile` 项目，一个 Makefile 插件会自动解析文件内容，并通过 `$registerTaskProvider` 将 `make build`, `make clean` 等命令注册为可执行任务，显示在“运行任务”列表中。

### 2.5 语言特性、SCM与搜索 (Language Features, SCM & Search)

#### `MainThreadLanguageFeatures`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 注册各类“语言智能”的提供者。这是 VSCode 语言服务（Language Server）生态能够工作的核心入口。
-   **关键方法**:
    -   `$registerCompletionsProvider(...)`: 注册自动补全提供者。
    -   `$registerHoverProvider(...)`: 注册悬停提示提供者。
    -   `$registerDefinitionSupport(...)`: 注册“跳转到定义”的提供者。
    -   `$registerCodeActionSupport(...)`: 注册代码操作（如快速修复、重构）的提供者。
    -   几乎所有 `vscode.languages.register...` 系列的 API 最终都会调用到这个服务。
-   **实现原理**: (当前为桩实现) `jetbrains_plugin` 侧的 `MainThreadLanguageFeatures` 仅打印日志。
-   **典型场景**: 用户安装了 `ESLint` 插件，该插件会调用 `$registerCodeActionSupport` 注册代码修复功能。当用户在有问题的代码上按 `Alt+Enter` 时，主进程会回调 `extension_host`，`ESLint` 插件返回“修复此问题”的指令，主进程再执行代码修改。

#### `MainThreadLanguages`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 提供与编程语言本身相关的功能。
-   **关键方法**:
    -   `$changeLanguage(resource, languageId)`: 强制改变一个文件的语言模式。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: 插件提供一个命令，允许用户将一个当前被识别为纯文本的文件，手动指定为 `JSON` 语言，从而获得语法高亮和格式化能力。

#### `MainThreadSCM`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: SCM 即 Source Control Management（源代码管理）。此服务允许插件在 IntelliJ 的“版本控制”工具窗口中创建一个新的源代码管理提供者。
-   **关键方法**:
    -   `$registerSourceControl(...)`: 注册一个新的 SCM 提供者。
    -   `$registerGroups(...)`: 在提供者内部注册文件分组（如 Git 的 "Staged Changes", "Changes"）。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: Perforce 插件通过此服务，在版本控制工具窗中添加一个 "Perforce" 标签页，并显示当前工作区中已修改的文件列表。

#### `MainThreadQuickDiff`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 允许插件为文件提供“快速差异”信息，通常显示在编辑器的行号区域（Gutter），用以表示当前行相对于某个基线版本（如上一次 Git 提交）的新增、修改或删除。
-   **关键方法**:
    -   `$registerQuickDiffProvider(...)`: 注册一个快速差异提供者。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: 当用户打开一个 Git 仓库中的文件时，VSCode 内置的 Git 插件通过此服务，将当前文件内容与 `HEAD` 版本进行比较，并在修改过的行旁边显示蓝色、绿色或红色的标记。

### 2.6 其他服务

#### `MainThreadExtensionService`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 接收 `extension_host` 关于插件生命周期的请求。
-   **关键方法**:
    -   `$activateExtension(extensionId)`: 请求主进程激活另一个插件。
-   **实现原理**: 委托给核心的 `ExtensionManager` 来处理插件的激活流程。
-   **典型场景**: 一个插件依赖于另一个插件的功能，它会先通过此服务确保其依赖插件已被激活。

#### `MainThreadStorage` & `MainThreadSecretState`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 为插件提供持久化存储能力。`Storage` 用于存储普通数据（对应 VSCode 的 `Memento` API），`SecretState` 用于安全地存储敏感数据（如 token）。
-   **实现原理**: `MainThreadStorage` 使用 `ExtensionStorageService`（其内部使用 `PropertiesComponent`）来存储键值对。`SecretState` 理论上应使用更安全的存储机制（如系统的钥匙串），但目前实现可能与 `Storage` 类似。
-   **典型场景**: 插件需要保存用户的设置或缓存数据，会调用 `Storage` 服务。当需要保存 API token 时，则会使用 `SecretState` 服务。

#### `MainThreadLogger`, `MainThreadConsole`, `MainThreadErrors`, `MainThreadOutputService`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 这些服务都是日志和输出的通道，允许 `extension_host` 将其内部的日志、错误和输出流信息发送到主进程。
-   **实现原理**: 接收到 `extension_host` 发来的日志信息后，使用 IntelliJ 的 `Logger` 将其写入到 IDE 的日志文件 (`idea.log`) 中。`OutputService` 则对应 VSCode 的“输出”面板，允许插件创建自己的输出频道。
-   **典型场景**: `extension_host` 进程启动失败，其错误信息会通过 `MainThreadErrors.$onUnexpectedError` 报告给主进程并记录下来，便于开发者排查问题。

#### `MainThreadProgress`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 在 IDE 界面上显示长时间运行任务的进度指示器。
-   **实现原理**: (当前未实现) `jetbrains_plugin` 侧未提供此服务的实现。
-   **典型场景**: 插件在执行一个耗时几秒钟的数据同步任务时，通过此服务在状态栏显示一个进度条，提升用户体验。

#### `MainThreadDiagnostics`
-   **方向**: `extension_host` -> `jetbrains_plugin`
-   **核心职责**: 接收来自 `extension_host` 的代码诊断信息（即代码中的错误、警告等），并在编辑器中显示出来。
-   **实现原理**: (部分实现) `jetbrains_plugin` 侧的 `ProblemManager` 实现了从 IDE 编辑器 (`DaemonCodeAnalyzer`) 获取诊断信息并**推送**到 `ExtHost` 的逻辑。但 `MainThreadDiagnostics` (即从 `ExtHost` **接收**诊断信息) 当前为桩实现。
-   **典型场景**: `ESLint` 插件在后台分析代码，发现一个语法错误，通过此服务将错误的位置和信息发送给主进程，用户就能在编辑器中看到红色的波浪线。

#### 其他 `MainThread...` 服务
-   **`MainThreadAuthentication`, `MainThreadComments`, `MainThreadDecorations`, `MainThreadDownloadService`, `MainThreadNotebook*`, `MainThreadTesting`, `MainThreadAi*`** 等。
-   **核心职责**: 这些大多是 VSCode 中特定功能的扩展点，例如：
    -   `Authentication`: 提供第三方登录（如 GitHub, Google）的能力。
    -   `Comments`: 允许插件在编辑器中创建评论区域（如 PR review）。
    -   `Decorations`: 允许插件自定义文件在文件浏览器中的图标和颜色。
    -   `Notebook*`: 全套的 Jupyter Notebook 支持。
    -   `Testing`: 提供测试的发现、运行和结果展示能力。
-   **实现原理**: (绝大部分当前为桩实现或未实现) 这些都是非常复杂的模块，需要与 IntelliJ 平台对应的功能进行深度桥接。

---

## 3. `ExtHostContext` 契约详解 (jetbrains_plugin -> extension_host)

### 3.1 文档与编辑器 (Documents & Editors)

#### `ExtHostDocumentsAndEditors`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将主进程中发生的文档和编辑器状态的**批量变化**通知给 `extension_host`。
-   **关键方法**:
    -   `$acceptDocumentsAndEditorsDelta(delta)`: 接收一个包含“新增的文档”、“移除的文档”、“新增的编辑器”、“移除的编辑器”和“新的活动编辑器”等信息的增量包。
-   **实现原理**: `extension_host` 端的 `ExtHostDocumentsAndEditors` 服务接收这个增量包，并据此更新其内部维护的文档和编辑器模型。这是保持两个进程状态同步的核心机制。
-   **典型场景**: 用户在 IntelliJ IDE 中打开了 2 个文件，关闭了 1 个文件，然后切换了活动标签页。主进程会将这 4 个事件打包成一个 `delta`，一次性发送给 `extension_host`。

#### `ExtHostDocuments`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将单个文档的精细变化通知给 `extension_host`。
-   **关键方法**:
    -   `$acceptModelChanged(uri, event, isDirty)`: 当文档内容发生变化时调用。
    -   `$acceptModelSaved(uri)`: 当文档被保存时调用。
    -   `$acceptDirtyStateChanged(uri, isDirty)`: 当文档的“脏”状态（即是否有未保存的修改）改变时调用。
-   **实现原理**: `extension_host` 端的 `ExtHostDocumentData` 会更新其内部状态，并触发 `vscode.workspace.onDidChangeTextDocument` 等 API 事件，让插件能够响应这些变化。
-   **典型场景**: 用户在编辑器中输入一个字符，主进程会捕捉到这个内容变化，并通过 `$acceptModelChanged` 将增量变化（例如，在第 5 行第 10 列插入了字符 "a"）通知给 `extension_host`。

#### `ExtHostDocumentContentProviders`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 当主进程需要获取虚拟文档的内容时，回调此服务。
-   **关键方法**:
    -   `$provideTextDocumentContent(handle, uri)`: 主进程请求获取指定 URI 的虚拟文档内容。
-   **实现原理**: `extension_host` 找到对应的 `TextDocumentContentProvider`（由插件开发者注册），调用其 `provideTextDocumentContent` 方法，并将返回的字符串内容回传给主进程。
-   **典型场景**: 承接 `MainThreadDocumentContentProviders` 的例子，当主进程需要显示 `git-commit://...` 的内容时，它会调用此方法来获取文件的具体文本。

#### `ExtHostDocumentSaveParticipant`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 在文档即将被保存时，允许插件参与并可能在保存前对文档进行修改。
-   **关键方法**:
    -   `$participateInSave(resource, reason)`: 通知 `extension_host` 指定的文档即将保存。
-   **实现原理**: `extension_host` 会触发 `vscode.workspace.onWillSaveTextDocument` 事件，插件可以监听此事件，执行诸如“保存时自动格式化代码”或“保存时移除行尾空格”等操作。这些操作会以文本编辑的形式返回给主进程，主进程应用这些编辑后再执行最终的保存。
-   **典型场景**: 用户按 `Ctrl+S` 保存一个 TypeScript 文件，`Prettier` 插件通过监听 `onWillSaveTextDocument` 事件，在文件写入磁盘前对整个文档进行格式化。

#### `ExtHostEditors`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将单个编辑器的状态变化（如选项、选择区域）通知给 `extension_host`。
-   **关键方法**:
    -   `$acceptEditorPropertiesChanged(id, props)`: 当编辑器的选项（如缩进大小、光标样式）、选择区域或可见范围发生变化时调用。
-   **实现原理**: `extension_host` 更新其内部维护的 `TextEditor` 对象的状态，并触发 `vscode.window.onDidChangeTextEditorOptions` 或 `vscode.window.onDidChangeTextEditorSelection` 等事件。
-   **典型场景**: 用户在编辑器中移动了光标，或选中了一段文本，主进程通过此方法将新的选择区域信息通知给 `extension_host`。

#### `ExtHostEditorTabs`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将编辑器标签页模型的变化通知给 `extension_host`。
-   **关键方法**:
    -   `$acceptEditorTabModel(tabGroups)`: 发送一个全新的、完整的标签页模型。通常在初始化时使用。
    -   `$acceptTabOperation(operation)`: 发送一个增量的标签页操作，如“打开”、“关闭”或“移动”。
-   **实现原理**: `extension_host` 根据这些数据更新其内部的 `TabGroups` 模型，并触发 `vscode.window.tabGroups.onDidChangeTabs` 事件。
-   **典型场景**: 用户在 IDE 中拖动一个标签页到另一个编辑器组，主进程会发送一个 `TAB_MOVE` 类型的 `operation` 来通知 `extension_host`。

#### `ExtHostEditorInsets`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将内联 Webview 的事件（如关闭、收到消息）通知给 `extension_host`。
-   **关键方法**:
    -   `$onDidDispose(handle)`: 当用户关闭了一个内联 Webview 时调用。
    -   `$onDidReceiveMessage(handle, message)`: 当内联 Webview 向主进程发送消息时，主进程再将此消息转发给 `extension_host`。
-   **实现原理**: (当前未实现) `extension_host` 找到对应的 `WebviewEditorInset` 对象并触发相应的事件。
-   **典型场景**: 用户在内联的 diff 视图中点击了“接受”按钮，该 Webview 将消息发送给主进程，主进程再通过 `$onDidReceiveMessage` 通知 `extension_host`，最终由插件执行接受代码的操作。

### 3.2 工作区与文件系统 (Workspace & Filesystem)

#### `ExtHostWorkspace`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 向 `extension_host` 提供当前工作区的信息，并通知其变化。
-   **关键方法**:
    -   `$initializeWorkspace(workspace, trusted)`: 在连接建立之初，发送当前工作区的完整信息（如项目名、根目录等）。
    -   `$acceptWorkspaceData(workspace)`: 当工作区信息发生变化（例如，用户通过 IDE 添加或删除了一个根目录）时，发送更新后的数据。
-   **实现原理**: `extension_host` 接收到这些数据后，会更新其内部的 `workspace` 对象，并触发 `vscode.workspace.onDidChangeWorkspaceFolders` 等事件，让插件能够响应工作区的变化。
-   **典型场景**: 用户通过 `File > Open` 打开了一个新的项目目录，主进程会通过 `$acceptWorkspaceData` 将新的文件夹信息通知给 `extension_host`。

#### `ExtHostFileSystem`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 当主进程需要通过插件注册的自定义文件系统提供者（`FileSystemProvider`）来操作文件时，回调此服务。
-   **关键方法**:
    -   `$readFile(handle, resource)`: 请求 `extension_host` 读取指定 URI 的内容。
    -   `$writeFile(handle, resource, content)`: 请求 `extension_host` 写入内容到指定 URI。
    -   其他文件操作如 `$stat`, `$readdir`, `$delete` 等。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 端的 `ExtHostFileSystem` 服务会找到对应的 `FileSystemProvider` 实例，并调用其 `readFile`, `writeFile` 等方法来执行操作。
-   **典型场景**: 用户在编辑器中打开了一个 `remote-fs://` 协议的文件并进行了修改和保存，主进程会通过 `$writeFile` 调用，让 `extension_host` 中的插件将文件内容发送回远程服务器。

#### `ExtHostFileSystemInfo`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 向 `extension_host` 通知文件系统的能力信息。
-   **关键方法**:
    -   `$acceptProviderInfos(uri, capabilities)`: 告知 `extension_host` 某个 URI scheme 对应的文件系统提供者的能力（例如，是否只读）。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 存储这些能力信息，以便在执行操作前进行检查。
-   **典型场景**: 一个只读的文件系统提供者被注册后，主进程通过此服务通知 `extension_host`，这样当插件尝试调用写入操作时，`extension_host` 可以提前抛出错误。

#### `ExtHostFileSystemEventService`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将主进程监听到的文件变化事件转发给 `extension_host`。
-   **关键方法**:
    -   `$onFileEvent(events)`: 发送一个包含文件创建、修改、删除事件的集合。
-   **实现原理**: `extension_host` 收到事件后，会触发 `vscode.workspace.onDidCreateFiles` 等一系列文件事件 API，让插件能够响应这些变化。
-   **典型场景**: 用户在项目目录外通过系统文件管理器创建了一个新文件，IDE 的文件监听器捕捉到这个变化，并通过此服务通知 `extension_host`。

#### `ExtHostConfiguration`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将配置信息和变更事件从主进程同步到 `extension_host`。
-   **关键方法**:
    -   `$initializeConfiguration(configModel)`: 在连接建立之初，发送完整的配置信息。
    -   `$updateConfiguration(configModel)`: 当配置发生变化时（例如，用户修改了 `settings.json`），发送更新后的配置。
-   **实现原理**: `extension_host` 维护一个配置对象的快照。当收到更新时，它会比较新旧配置的差异，并触发 `vscode.workspace.onDidChangeConfiguration` 事件，插件可以通过这个事件来获取最新的配置值。
-   **典型场景**: 用户在 IntelliJ 的设置中修改了由插件贡献的配置项，主进程将此变更通过 `$updateConfiguration` 通知 `extension_host`，插件随即重新加载其配置。

#### `ExtHostSearch`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 当主进程需要通过插件注册的自定义搜索提供者进行搜索时，回调此服务。
-   **关键方法**:
    -   `$provideFileSearchResults(handle, session, query)`: 请求 `extension_host` 提供文件搜索结果。
    -   `$provideTextSearchResults(handle, session, query)`: 请求 `extension_host` 提供文本搜索结果。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 找到对应的搜索提供者插件，调用其 `provideFileSearchResults` 等方法，并将结果流式地返回给主进程进行显示。
-   **典型场景**: 承接 `MainThreadSearch` 的例子，当用户搜索时，主进程通过 `$provideFileSearchResults` 回调 `extension_host`，让插件在远程服务器上执行搜索并将结果返回。

### 3.3 用户界面与交互 (UI & Interaction)

#### `ExtHostCommands`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 允许主进程执行在 `extension_host` 中注册和实现的命令。
-   **关键方法**:
    -   `$executeContributedCommand(id, args)`: 请求 `extension_host` 执行一个由插件贡献的命令。
-   **实现原理**: `extension_host` 维护一个自己的命令注册表。当收到请求时，它会找到并执行对应的 TypeScript/JavaScript 函数。
-   **典型场景**: 一个由 IntelliJ 原生 UI（例如一个菜单项）触发的动作，需要调用一个由 VSCode 插件实现的功能。

#### `ExtHostWindow`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将主窗口的状态变化（如焦点变化）通知给 `extension_host`。
-   **关键方法**:
    -   `$onDidChangeWindowFocus(value)`: 当 IDE 窗口获得或失去焦点时调用。
-   **实现原理**: `extension_host` 收到通知后，会更新其内部状态并触发 `vscode.window.onDidChangeWindowState` API 事件。
-   **典型场景**: 插件需要在 IDE 窗口激活时执行某些操作（如刷新数据），而在窗口失活时暂停操作（如停止轮询）。

#### `ExtHostWebviews`, `ExtHostWebviewPanels`, `ExtHostWebviewViews`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将来自 Webview 的事件转发给 `extension_host`。
    -   `ExtHostWebviews`: 负责基础通信，主要是将 Webview 通过 `acquireVsCodeApi().postMessage()` 发送的消息，通过 `$onMessage` 转发给 `extension_host`。
    -   `ExtHostWebviewPanels`: 转发编辑器式 Webview 的特定事件，如状态变化 (`$onDidChangeWebviewPanelViewStates`) 和关闭事件 (`$onDidDisposeWebviewPanel`)。
    -   `ExtHostWebviewViews`: 转发侧边栏式 Webview 的特定事件，如可见性变化 (`$onDidChangeWebviewViewVisibility`) 和关闭事件 (`$disposeWebviewView`)。
-   **实现原理**: 消息从 Webview 的 JavaScript -> `JBCefBrowser` 的 `JSQueryHandler` -> JetBrains 插件 -> RPC 调用 -> `extension_host` 的这些服务 -> 最终触发插件中对应的 `Webview.onDidReceiveMessage` 等 API 事件。
-   **典型场景**: 用户在 Roo Code 的聊天界面中点击“发送”按钮，前端代码调用 `postMessage`，消息最终通过 `ExtHostWebviews.$onMessage` 到达 `extension_host` 中的插件逻辑进行处理。

#### `ExtHostQuickOpen`, `ExtHostStatusBar`, `ExtHostTreeViews`, `ExtHostUrls`, `ExtHostUriOpeners`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 这些服务主要负责将用户与相应 UI 组件的交互事件回调给 `extension_host`。
-   **实现原理**: (大部分当前未实现)
    -   `ExtHostQuickOpen`: 当用户在快速选择列表中选择一项时，主进程会通过 `$onItemSelected` 通知 `extension_host`。
    -   `ExtHostTreeViews`: 当用户展开树节点时，主进程通过 `$getChildren` 请求子节点数据。
-   **典型场景**: 用户展开了 GitLens 插件的提交历史树中的一个节点，主进程调用 `$getChildren`，`extension_host` 中的 GitLens 插件逻辑负责返回该提交下的文件列表。

### 3.4 终端、调试与任务 (Terminal, Debug & Tasks)

#### `ExtHostTerminalService`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将主进程中终端发生的所有事件通知给 `extension_host`。
-   **关键方法**:
    -   `$acceptTerminalOpened(...)`: 当一个新终端被创建时调用。
    -   `$acceptTerminalClosed(...)`: 当一个终端被关闭时调用。
    -   `$acceptTerminalProcessData(id, data)`: 当终端产生输出时，将输出数据块发送给 `extension_host`。
    -   `$acceptDidExecuteCommand(id, command)`: 当通过 shell integration 追踪到一个命令执行完毕后调用。
-   **实现原理**: `extension_host` 端的 `ExtHostTerminalService` 维护着所有终端实例的代理对象。当收到这些事件时，它会更新对应代理对象的状态，并触发 `vscode.window.onDidOpenTerminal`, `vscode.window.onDidCloseTerminal`, `Terminal.onDidWriteData` 等 API 事件。
-   **典型场景**: 用户在终端里执行 `ls` 命令，底层的 PtyProcess 产生输出，主进程通过 `$acceptTerminalProcessData` 将输出发送给 `extension_host`，插件可以通过 `onDidWriteData` 事件获取到 "file1.txt file2.txt" 这些文本。

#### `ExtHostTerminalShellIntegration`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将 shell integration 相关的精确事件通知给 `extension_host`。
-   **关键方法**:
    -   `$shellExecutionStart(...)`: 当 shell integration 检测到一个新命令开始执行时调用。
    -   `$shellExecutionEnd(...)`: 当命令执行结束时调用，包含退出码。
    -   `$cwdChange(...)`: 当终端的当前工作目录（CWD）发生变化时调用。
-   **实现原理**: `extension_host` 收到这些精确的事件后，可以为插件提供更丰富的 API，例如 `vscode.window.onDidStartTerminalCommand`。
-   **典型场景**: 一个需要知道当前终端 CWD 的插件，可以监听 `onDidEndTerminalCommand` 事件，并从事件参数中获取到命令执行完毕后终端所在的最新目录。

#### `ExtHostDebugService`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将来自 IntelliJ 调试器或用户的调试事件转发给 `extension_host` 中的调试插件。
-   **关键方法**:
    -   `$acceptDebugSessionStarted/Terminated`: 通知调试会话的开始和结束。
    -   `$acceptBreakpointsDelta`: 当用户在 IDE 界面中添加或移除了断点时，将这些变化通知给 `extension_host`。
    -   `$sendDAMessage`: 将来自调试器的 DAP 消息（例如，一个断点命中事件）发送给 `extension_host`。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 收到这些事件后，会调用调试插件中对应的 `DebugAdapter` 方法，或者触发 `vscode.debug.onDidChangeBreakpoints` 等 API 事件。
-   **典型场景**: 用户在 IntelliJ 编辑器的行号区域点击添加了一个断点，主进程通过 `$acceptBreakpointsDelta` 将这个新断点的信息发送给 `extension_host`，`extension_host` 再通过 DAP 协议将其发送给正在运行的 Debug Adapter。

#### `ExtHostTask`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 回调 `extension_host` 来获取由插件动态提供的任务。
-   **关键方法**:
    -   `$provideTasks(handle)`: 当用户想要运行任务时，主进程通过此方法向 `extension_host` 请求任务列表。
    -   `$resolveTask(handle, taskDTO)`: 在执行任务前，允许插件对任务定义进行最终的解析和修改（例如，替换变量）。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 找到对应的 `TaskProvider` 插件，调用其 `provideTasks` 方法，并将返回任务列表回传给主进程显示在 UI 上。
-   **典型场景**: 用户从菜单中选择“运行任务...”，主进程调用 `$provideTasks`，`Makefile` 插件执行 `make -p` 命令，解析其输出，并返回一个包含 `build`, `clean` 等任务的列表。

### 3.5 语言特性、SCM与搜索 (Language Features, SCM & Search)

#### `ExtHostLanguageFeatures`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 当需要获取语言智能信息时，主进程通过此服务回调 `extension_host` 中对应的提供者。
-   **关键方法**:
    -   `$provideCompletionItems(...)`: 请求代码补全建议。
    -   `$provideHover(...)`: 请求悬停信息。
    -   `$provideDefinition(...)`: 请求“跳转到定义”的位置。
    -   `$provideCodeActions(...)`: 请求可用的代码操作。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 收到请求后，会带上当前文档、光标位置等上下文信息，调用插件注册的 `CompletionItemProvider`, `HoverProvider` 等，并将插件返回的结果传回主进程。
-   **典型场景**: 用户在编辑器中输入 `.`，触发了自动补全。主进程通过 `$provideCompletionItems` 调用 `extension_host`，`extension_host` 中的 TypeScript 语言服务插件分析代码上下文，返回一个包含所有可用属性和方法的列表，主进程再将此列表显示为补全菜单。

#### `ExtHostLanguages`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将语言相关的事件通知给 `extension_host`。
-   **关键方法**:
    -   `$acceptLanguageIds(ids)`: 将 IDE 中所有已知的语言 ID 列表同步给 `extension_host`。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 维护一份语言列表，用于 `vscode.languages.getLanguages()` API。
-   **典型场景**: 插件启动时，需要知道当前 IDE 支持哪些语言，以便为特定语言注册功能。

#### `ExtHostSCM`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将用户在 SCM UI 上的交互事件回调给 `extension_host`。
-   **关键方法**:
    -   `$onInputBoxValueChange(...)`: 当用户在 SCM 面板的提交信息框中输入时调用。
    -   `$executeResourceCommand(...)`: 当用户点击了某个文件旁边的命令按钮（例如，“暂存文件”）时调用。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 收到事件后，会调用插件注册的 `SourceControl` 对象上的相应方法来处理用户输入或执行 Git/Perforce 等命令。
-   **典型场景**: 用户在 Git 插件提供的文件列表上，点击了某个文件右侧的“+”号图标，主进程通过 `$executeResourceCommand` 通知 `extension_host`，Git 插件随即执行 `git add <file>` 命令。

#### `ExtHostQuickDiff`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 当主进程需要为某个文件生成快速差异时，通过此服务回调 `extension_host` 来获取文件的原始版本内容。
-   **关键方法**:
    -   `$provideOriginalResource(uri)`: 请求获取指定 URI 对应文件的基线版本内容。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 找到对应的 `QuickDiffProvider`（通常是 Git 插件），调用其 `provideOriginalResource` 方法。该方法会执行类似 `git show HEAD:<file>` 的命令来获取文件的上一个版本内容，并将其 URI 返回给主进程。
-   **典型场景**: 主进程准备在编辑器行号区域显示差异标记，它会先通过此服务获取文件的原始 URI，然后自己进行内容比较并渲染标记。

### 3.6 其他服务

#### `ExtHostExtensionService`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 主进程通过此服务来控制 `extension_host` 的核心生命周期。
-   **关键方法**:
    -   `$startExtensionHost(...)`: 启动 `extension_host` 进程。
    -   `$activate(...)`: 命令 `extension_host` 激活一个特定的插件。
    -   `$activateByEvent(...)`: 当某个激活事件（如 `onLanguage:typescript`）发生时，通知 `extension_host` 去激活所有监听该事件的插件。
-   **实现原理**: 这是插件懒加载机制的核心。`extension_host` 维护了所有插件的元数据和激活事件。当收到激活命令时，它会 `require` 插件的入口文件并调用其 `activate` 方法。
-   **典型场景**: 用户打开一个 `.ts` 文件，主进程检测到后，通过 `$activateByEvent` 发送 `onLanguage:typescript` 事件，`extension_host` 随即激活 TypeScript 语言服务插件。

#### `ExtHostStorage` & `ExtHostSecretState`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 当存储在主进程的数据发生变化时（例如，由于设置同步），通知 `extension_host` 更新其缓存。
-   **关键方法**:
    -   `$acceptValue(...)`: 将更新后的存储内容发送给 `extension_host`。
-   **实现原理**: `extension_host` 中的 `ExtensionStorage` 类接收到新数据后，会更新其内存中的 `Memento` 对象，并通过事件通知插件数据已变更。
-   **典型场景**: 用户在另一台设备上修改了设置，并通过云端同步到了当前设备，主进程更新存储后，通过此服务通知 `extension_host` 更新插件缓存。

#### `ExtHostDiagnostics`
-   **方向**: `jetbrains_plugin` -> `extension_host`
-   **核心职责**: 将主进程（例如，IntelliJ 自身的代码检查器）产生的诊断信息同步给 `extension_host`。
-   **关键方法**:
    -   `$acceptMarkersChange(...)`: 发送最新的标记（问题）列表。
-   **实现原理**: (当前无独立的 `Proxy` 文件) `extension_host` 更新其内部维护的诊断集合，并触发 `vscode.languages.onDidChangeDiagnostics` 事件。
-   **典型场景**: IntelliJ 内置的 Java 编译器发现了一个错误，主进程可以将这个错误信息通过此服务发送给 `extension_host`，让所有插件都能知晓这个问题的存在。

#### 其他 `ExtHost...` 服务
-   **`ExtHostAuthentication`, `ExtHostComments`, `ExtHostDecorations`, `ExtHostProgress`, `ExtHostTelemetry`, `ExtHostNotebook*`, `ExtHostTesting`** 等。
-   **核心职责**: 这些服务是 `MainThread` 对应服务的另一半，负责接收来自用户的交互事件或主进程的状态更新。
-   **实现原理**: (绝大部分当前未实现)
    -   `ExtHostAuthentication`: 当用户在主进程弹出的登录框中完成登录后，主进程通过此服务将获取到的 session 信息发送给 `extension_host`。
    -   `ExtHostComments`: 当用户在编辑器中创建或回复一条评论时，主进程将这些交互事件通知给 `extension_host` 中对应的插件进行处理。
    -   `ExtHostProgress`: 当用户点击了进度通知上的“取消”按钮时，主进程通过 `$acceptProgressCanceled` 通知 `extension_host` 中发起该任务的插件。
