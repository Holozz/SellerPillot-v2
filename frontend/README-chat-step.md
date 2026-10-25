# 前端第二步：当前店铺的运营问答

## 放入项目

本轮交付包包含4个src文件：

- `api.ts`、`App.tsx`、`style.css`：替换项目frontend/src中的同名文件。
- `AgentChat.tsx`：新增到frontend/src。

包内的src目录应合并到 `D:/Project/SellerPilot/frontend/src`，保持目录层级。用户已把开发端口改为5188，本轮只交付src文件，保留实际vite.config.ts。

已启动的前端开发服务会自动更新，继续访问 http://127.0.0.1:5188 。未启动时，在frontend目录执行npm run dev。后端沿用自己终端中127.0.0.1:8000的服务。

## 本轮流程

登录 → 选择店铺 → 输入问题并发送 → askAgent调用现有Agent接口 → 后端返回答案和conversation_id → 页面显示问答并记住会话ID → 下次提问携带该ID。

实际接口保持后端已有的单数store路径：

```text
POST /store/{store_id}/agent/query
```

首次请求体为 `{"message":"哪些商品的库存低于10？"}`。追问请求增加 `conversation_id`。后端响应多出的tool_calls字段仍由后端返回，当前页面不展开工具原始结果。

## 新增函数和结构

| 项目 | 作用、输入与输出、参与流程 |
| --- | --- |
| `PolicyCitation` | TypeScript类型说明，描述后端提供的原文章节、标题、内容与版本；不发请求，也不做运行时语义校验。 |
| `AgentReply` | 描述answer、citations、可选policy_status和会话ID/版本。类型声明不限制响应携带其他字段。 |
| `askAgent` | 输入店铺ID、问题、AbortSignal和可选会话ID，复用request发送POST；返回Promise<AgentReply>。JWT由原request统一附带。 |
| `AgentChat` | 独立React组件，输入当前店铺及登录过期回调；管理问题、问答记录、会话ID和发送状态，返回聊天页面。 |
| `handleSend` | 接收表单事件、去掉问题首尾空白、阻止重复请求；调用askAgent，成功后追加问答并保存conversation_id。失败保留输入和已成功问答，401交给父组件退出登录。 |
| `handleNewConversation` | 清空当前页面问答、输入、错误和会话ID。下一次提问不带旧ID，后端创建新会话；不会删除已保存的后端会话。 |

App新增的onSessionExpired回调复用handleLogout清理账号，再显示登录过期提示。现有登录/店铺查询函数保持原用途。

## 关键React语法

`<AgentChat store={selectedStore} ... />` 是组件组合：App负责账号与店铺，AgentChat负责选中店铺内的聊天。传给组件的数据和函数叫props。

``key={`${user.id}:${selectedStore.id}`}`` 把组件身份绑定到用户和店铺。切换店铺时，React卸载旧聊天组件并创建新实例，它的turns和conversationId因此从初始值开始；切回原店铺也创建新会话，不恢复以前的页面记录。刷新店铺列表且用户/店铺未变化时，该组件保持挂载。

`useRef<AbortController | null>(null)` 保存当前浏览器请求的控制器。ref.current可跨组件重新渲染保存值，修改它不会触发页面重画。它还作为立即生效的发送锁，防止在sending状态更新前发生连续提交。

`useEffect(() => () => pending.current?.abort(), [])` 在组件卸载时执行清理。切换店铺或退出后，旧浏览器请求会被取消等待，后续旧回复也会被忽略。取消fetch不等于取消后端运行：已经发出的模型请求或会话保存仍可能完成。

`setTurns(previous => [...previous, newTurn])` 读取上一份状态，创建包含旧问答及本轮问答的新数组。这里不直接修改原数组。

`conversationId?: number` 表示可选参数。首次值为undefined，JSON.stringify会省略该字段；成功后从回复保存ID，追问再带上它。

## 展示与范围

- 支持库存、订单、销售汇总等既有只读工具和演示政策问答。示例按钮只填入输入框，用户点击发送才调用模型。
- 当前使用一次完整HTTP响应，等待期间显示正在处理；尚未接入SSE逐字输出。
- 政策引用可展开查看后端返回的原文及版本。答案和原文都按普通文本渲染，不执行其中HTML，也没有加入Markdown解析依赖。
- 资料不足时显示后端答案与资料不足标记。引用合法不等于答案在语义上必然正确，仍沿用后端的已知能力边界。
- 页面刷新、退出或切换店铺会结束当前页面保存的聊天上下文。后端会话已保存，但当前尚无历史会话列表与恢复接口。
- 每次页面只允许一个待完成问题；本轮不增加请求幂等ID。网络中断后的人工重试可能让后端再次运行，不能承诺取消或重试完全不产生重复会话轮次。

## 验收

1. 登录并选店铺，出现“运营问答”。发送“哪些商品的库存低于10？”，看到实际回答。
2. 追问“改成低于20呢？”，应由同一后端会话理解追问。
3. 新对话后询问演示退货政策，展开原文；再问未覆盖的保修期，观察资料不足提示。
4. 切换店铺，上一店铺的页面记录清空，新问题不沿用旧conversation_id。
5. 等待回答时切换店铺，旧回答不能显示到新店铺中。失败时应保留输入，允许用户重试。

## 验证证据

TypeScript检查和生产构建通过。无窗口Edge完成九组浏览器流程检查，包括续接会话、引用展开与纯文本安全、失败保留输入、重复提交、切店旧回复隔离、刷新店铺保持会话、手机布局与401清理。报告和截图位于协作目录sellerpilot_frontend_chat_verification。

上述浏览器问答使用模拟接口，没有调用真实GLM或写业务数据；复制到实际项目后仍需真实账号与模型联调。测试使用独立的临时前端进程，结束后已关闭。
