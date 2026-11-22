# 用户空间页（`SpaceActivity`）与空间内搜索页（`SpaceSearchActivity`）前端与架构设计文档

> 本文档在编码前制定，严格遵循 YuiBili 极简柔和现代风设计系统（`Ds` / `MotionTokens` / `Theme`），并融合「后端与性能指导顾问」核定的 B 站接口契约、风控与性能优化约束。

---

## 一、设计目标与克制边界

1. **视觉与交互原则**：
   - 不机械复刻官方 B 站厚重杂乱的空间头图/装扮堆砌，采用 YuiBili 极简暖白 / 碳墨阶梯底色 + 单一柔和玫瑰粉强调色（`COLOR_ROSE`），通过轻量卡片描边（`Ds.colors.lineSoft`）、充裕留白和硬件加速圆角裁剪构建高级通透质感。
   - 严禁大段说明文字堆砌、严禁实时模糊与重投影、遵循系统减弱动画偏好（`MotionTokens.isReduced`）。
2. **功能克制范围（严格按用户要求）**：
   - **空间头部仅包含**：头像、昵称、等级（`LV0`~`LV6`）、大会员标识（仅 `vipStatus == 1` 时按 `vipLabel` 显示，如「大会员」「年度大会员」）、签名（点击可展开完整多行/收起）、粉丝数、关注数、获赞数、关注/已关注按钮。
   - **明确不做**：无「编辑资料」按钮；无空间头图背景墙/勋章墙/直播/收藏/追番等无关模块；动态卡片时间右侧**明确不做三个点（`...`）更多菜单**。
   - **空间内搜索**：进入时内容区为空白提示态（不预拉全量数据）；输入框下方支持左右滑动切换「动态」与「视频」双标签；结果卡片结构与空间主页对应标签**完全一致**（复用同一套卡片渲染器）。

---

## 二、入口与导航流转

1. **页面打开方式**：
   - `SpaceActivity.open(context, mid, initialName = "", initialFace = "")`：传入 `mid`（`> 0L`）必选，同时可传入已知的昵称和头像 URL 作为首帧即时占位，消除白屏等待感；通过 `overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left_soft)` 右滑入，返回时 `slide_in_left_soft / slide_out_right`。
   - `SpaceSearchActivity.open(context, mid, upName, initialTab = 0)`：携带当前空间 `mid`、UP 主昵称（用于搜索框提示「搜索 TA 的动态或投稿」）及当前所在 Tab，搜索栏默认自动聚焦弹出键盘。
2. **全应用空间跳转入口**：
   - **视频详情页（`VideoDetailActivity`）**：
     - 简介页顶部 UP 主信息行（`upRow` 的头像与昵称/信息区域点击进入 UP 主空间，右侧关注按钮保持独立点击不冲突）。
     - 评论区（主评论列表与楼中楼弹窗）点击用户头像或用户昵称进入对应用户空间（当 `comment.userMid > 0L`）。
   - **「我的」页面（`AccountPageView`）**：
     - 已登录资料卡的头像/昵称区域增加点击进入自己空间（`mid = info.uid`），右侧退出登录按钮保持独立点击区。
   - **其他页面可点击入口**：
     - 首页/搜索双列视频卡片或历史/收藏行若后续无歧义保持视频播放直达；视频详情内已完整覆盖 UP 主与评论作者跳转；同时动态卡片中若存在转发原动态作者（`orig.authorMid > 0L`），点击原动态作者头像/昵称也可进入该原动态作者空间，支持空间链式跳转。

---

## 三、空间主页（`SpaceActivity`）前端结构与交互设计

### 3.1 整体层级结构（自上而下）

```
Root (LinearLayout, VERTICAL, bg = COLOR_BACKGROUND)
├── TopBar (52dp)
│   ├── 返回按钮 (40dp, Ds.backIcon)
│   ├── 顶栏吸顶标题 (alpha 0 -> 1 随滚动平滑显现：UP主头像小图 + 昵称)
│   └── 空间内搜索按钮 (40dp, Ds.searchIcon -> 打开 SpaceSearchActivity)
├── 联动滚动容器 / 头部 + 双Tab Pager
│   ├── SpaceHeaderCard (可随下方列表协同折叠或作为吸顶下方的自适应头区)
│   ├── 双标签栏 (动态 | 投稿) + PillIndicatorView 玫瑰粉柔雾滑块
│   └── RootPager (左右手势滑动切换，两页始终 VISIBLE 仅 translationX 移动)
│       ├── Page 0: 动态列表（Recycler/ListView 或高效复用列表）
│       └── Page 1: 投稿视频列表（双列 VideoCardUi 或统一紧凑投稿卡，支持排序切换）
```

> **联动滚动体验优化（关键）**：
> 为了让「头部信息卡片」和「动态 / 投稿双标签页」在上下滑动时拥有原生丝滑的联动体验（下滑列表时头部自然上滑收起，只将「动态 | 投稿」标签栏吸顶保留在顶部，左右切 Tab 后也能顺滑继续浏览）：
> - 采用自定义轻量协调容器 `SpaceNestedContainer`（或外层监听子列表滚动偏移联动翻译头部，同时将 `TabBar` 吸顶），或者更稳妥低风险的双段联动结构：
>   - 头部区放置在固定槽位并随当前激活 Tab 列表的顶部留白同步位移（`translationY` 随列表 `scrollY` 在 `[-headerHeight, 0]` 区间联动），列表本身带一个不可见的 `headerSpacer`（高度 = `headerHeight`），**从而既支持 `ListView` 视图回收（零视图堆积、流畅不卡顿）、又让头部自然跟随滚动滑出视野且 `TabBar` 自动吸顶**！
>   - 切换 Tab 时，自动平滑对齐另一 Tab 的顶部偏移，防止头部半截错位。

### 3.2 空间头部设计（克制精致、信息清晰）

采用一张干净的轻描边柔和圆角卡片（`Ds.Radius.card`，`COLOR_CARD` + `1px lineSoft`，内边距 `16dp`）：
1. **第一行（身份与操作）**：
   - 左侧：`64dp` 圆形头像（`RoundAvatar`，支持点击查看大图），若有传入 `initialFace` 秒出显示，同时不闪占位符。
   - 中间（垂直排列）：
     - 昵称：`19sp` `Ds.display(bold = true)`，单行截断 `END`，若为本人空间显示与普通一致的清晰姓名，无多余装饰。
     - 徽章行（紧随昵称下方 `6dp`）：
       - **等级标签**：`LV0`~`LV6` 紧凑小圆角胶囊（`10sp`，`COLOR_SOFT_FILL` 底 + `COLOR_MUTED` 字；LV5/LV6 可微弱暖灰区分不过分花哨）。
       - **大会员标签**：仅当 `vipLabel` 非空（`vipStatus == 1`）时展示，如 `大会员` / `年度大会员`（`COLOR_ROSE_SOFT` 底 + `COLOR_ROSE` 字体）。
   - 右侧（**关注 / 取消关注按钮**）：
     - `isSelf == true`（本人空间）：隐藏关注按钮，可展示轻量灰底静态标识「我的空间」（无编辑资料按钮）。
     - `FollowState.UNKNOWN`（未登录或查询尚在未知/无凭证态）：显示「关注」态但点击提示「请先登录后再关注」（若接口未确认状态且已登录则显示加载中禁用点击，避免误触）。
     - `FollowState.NOT_FOLLOWED`：玫瑰粉实心胶囊「+ 关注」（`COLOR_ROSE` 底 + `COLOR_ON_ROSE` 字），带 `Ds.bindPressFeedback` 与轻弹动画。
     - `FollowState.FOLLOWED`：浅灰描边胶囊「已关注」（`COLOR_FOLLOWED_CHIP` 底 + `COLOR_MUTED` 字），点击即取消关注（防连点 `followInFlight` 锁，乐观更新 + `LikeStateBridge.rememberFollow`，失败自动回滚并 Snackbar 提示具体原因如 `-101`/`22001`/`22002`）。
2. **第二行（个性签名）**：
   - 展示 `sign`（空签名展示极淡的「这个人很懒，什么都没有写」或直接留白省略——为保持极简，空签名时展示 `暂无个性签名` 或收起节省空间，这里若 `sign.isNotBlank()` 展示签名文本；若超过 2 行，点击签名整行平滑展开/收起全部签名）。
3. **第三行（三项核心数据统计条）**：
   - 一条 `hairline` 分隔线下方等分 3 列（`tnum` 等宽数字 `16sp` 加粗 + `11.5sp` 标签）：
     - **粉丝数**（`formatViews` 紧凑格式化：`1.2万` / `12.5万` / `986`，未知显示 `--`）
     - **关注数**
     - **获赞数**（严格区分 `null` 与 `0`，登录有效返回即展示真实值，未登录/受限/失败时显示 `--`）

### 3.3 双标签栏与左右滑动

- 标签：`动态`、`投稿`（居中或左对齐均分两枚大标签，底部/底色使用同款 `PillIndicatorView` 玫瑰粉柔雾胶囊滑块，滑动过程中 `RootPager.setOnPageScrollListener(progress)` 实时线性插值跟随滑块位置与文字颜色！）。
- 容器：复用并适配 `RootPager`（两页始终 `VISIBLE`，仅用 `translationX` 位移，支持跟手拖拽和 `SpringForce` 弹性落位；到达边界时禁用反向拦截；未选中的 Tab 在首次真正切入或滑露时再懒加载首屏数据，节省请求与风控额度）。

### 3.4 「动态」标签设计（支持置顶 / 纯文字 / 图文多图 / 视频 / 转发）

共享组件 `SpaceDynamicCardUi`（同时被 `SpaceActivity` 与 `SpaceSearchActivity` 复用）：
- **卡片外壳**：`Ds.card`（`16dp` 圆角、`COLOR_CARD`、细描边 `lineSoft`，卡片间距 `10dp`），内边距 `14dp`。
- **卡片头部（作者行 + 置顶，严格无三点菜单）**：
  - 左侧：`36dp` 圆形头像 + 右侧作者昵称（`14sp` 加粗）与发布时间（`11.5sp` `subtle`，如 `今天 18:20` / `11-18` / `2025-09-01`）。
  - 右侧：**仅在 `item.isPinned == true` 时显示一个柔和玫瑰粉「置顶」小胶囊**（`COLOR_ROSE_SOFT` + `COLOR_ROSE`）；**时间右侧及卡片右上角绝不渲染任何「...」三个点按钮**。
- **动态正文区**：
  - 使用 `SpannableStringBuilder` 渲染正文，支持内置小表情图标（复用轻量 `InlineEmotes` 或统一 `SpaceEmoteSpan` 从 `richNodes.emojiUrl` 行内加载），保留换行；超过 6 行时支持折叠并在右下角显示柔和「展开全文 / 收起」按钮，点击文本展开而非跳转误触。
- **主体区（按 `DynamicKind` 差异化优雅呈现）**：
  1. **`TEXT`（纯文字）**：仅正文 + 底部轻量统计栏，干净利落。
  2. **`IMAGE`（图文单图 / 多图九宫格）**：
     - **1 张图**：自适应宽高比展示（最大宽度填满内容区，限高防止超长图占满几屏，保持优雅裁剪/圆角 `12dp`），长图/超宽图右上角贴一枚半透「长图」小角标。
     - **2 或 4 张图**：2 列等比圆角网格（`6dp` 间距，`10dp` 圆角）；4 张为 2×2 田字格，视觉更均衡。
     - **3 或 5~9 张图**：3 列正方形圆角九宫格（`6dp` 间距，`10dp` 圆角），最多展示 9 张，超 9 张末格叠加半透 `+N` 遮罩。
     - 点击任意图片：**打开支持双指缩放、双击放大、`BitmapRegionDecoder` 长图分条解码与左右滑动切换的大图弹窗**（抽取 `VideoDetailActivity` 的 `ZoomableImageView` / `ImageViewerDialog` 为独立公共类 `SpaceImageViewer` 或 `ImagePreviewDialog`，两处共用不重复造轮子）。
     - 缩略图加载均使用 `CoverLoader.load(..., thumbEdge = ..., priority = ...)` 带 `@...webp` 尺寸后缀，点击大图才加载原图。
  3. **`VIDEO`（视频投稿动态）**：
     - 嵌套一张精致的横向或大图视频预览卡（`COLOR_SURFACE` 底、`12dp` 圆角、细描边）：
       - 16:10 圆角封面（左侧或顶部宽图封面的统一卡片风格——建议顶部 16:9 宽图封面或左图右文卡片；**动态流内用「16:9 封面 + 右下时长徽章 + 封面下方 2 行标题 + 播放/弹幕数」的紧凑大卡片**，视觉比缩成小方块更有呼吸感且不易截断长标题）。
       - 点击整张视频子卡跳转 `VideoDetailActivity.open(context, bvid, aid)`。
  4. **`FORWARD`（转发动态）**：
     - 外层展示当前用户的转发附言；
     - 下方嵌套一块柔灰底色（`COLOR_SURFACE`、`12dp` 圆角、内边距 `12dp`）的原动态引用块：
       - 若原动态无效（`original == null` 或失效标识），显示克制的居左提示「原动态已失效或已被删除」。
       - 否则展示 `@原作者昵称`（可点击跳转原动态作者空间）+ 原动态正文（最多 4 行可看全文）+ 原动态图片网格或原动态视频预览卡（同样支持点击看大图 / 跳转播放）。
  5. **`UNKNOWN`（专栏/番剧/直播/音乐/不可见等）**：
     - 若 `visible == false` 或无有效主体：展示克制的灰底提示条「该动态暂不支持查看」或只读标题摘要；若附带合理 `jumpUrl` 且能提取 `BV`/`av`，支持直达播放器，否则只读展示不崩溃、不伪造视频。
- **底部轻量统计行（只读展示，极简一行）**：
  - 转发数 / 评论数 / 点赞数（自绘细线小图标 + 等宽数字 `11.5sp` `subtle`，0 时显示文字「转发 / 评论 / 赞」或直接省略 0 值保持更干净——显示细线图标 + 数值，若为 `null` 则显示 `--` 或隐藏）。严格只读，不实现评论/转发/点赞交互。

### 3.5 「投稿」标签设计（UP 主视频列表）

- **顶部轻量排序条**（悬浮/吸顶在投稿列表顶端或列表首行）：
  - 左侧 3 个轻量胶囊排序：`最新发布`（`pubdate`）、`最多播放`（`click`）、`最多收藏`（`stow`），右侧展示当前总投稿数（如 `共 128 个投稿`）。
- **列表布局**：
  - 复用 `VideoCardUi`（与首页推荐/热门及站内搜索完全同款的 2 列双排视频卡片！`16:10` 封面、左下播放量/弹幕量、右下时长、2 行标题、底部展示投稿发布日期与播放元信息），保证全站视频卡片风格 100% 统一；支持上拉分页加载（`ps=20` 滚动提前 6 项触发下一页）与底部 `BrandSpinnerView` / 末尾「没有更多了」提示 / 失败点击重试。
  - 点击视频卡片直接跳转 `VideoDetailActivity.open(context, bvid, aid)`。

---

## 四、空间内搜索页（`SpaceSearchActivity`）前端结构与交互设计

1. **顶栏搜索框**：
   - 完全对齐 `HistorySearchActivity` / `FavoriteSearchActivity` 规范：`40dp` 高度圆角胶囊框（`Ds.pill`）、左侧返回图标、放大镜图标、`EditText`（`14sp`，`includeFontPadding = false`，单行、`IME_ACTION_SEARCH`）、输入非空时右侧显示清空 `×` 按钮；进入页面自动弹出软键盘。
   - 不在输入过程中高频盲发 WBI 搜索请求（防 `-352`/`-799` 风控）：按顾问建议**提交/键盘搜索键**（以及顶栏右侧紧凑「搜索」文字按钮，方便不习惯按输入法回车的用户）触发请求；清空输入框或点击清除按钮回到空白态。
2. **双标签栏（动态 | 视频）**：
   - 紧贴顶栏分割线下方，高度 `40dp`，支持**点击切换**与底部 `RootPager` **左右手势滑动切换**，`PillIndicatorView` 滑块连续跟随。
   - 当输入框为空时，双标签页内容区均展示居中的柔和空白提示态（「输入关键词搜索该 UP 主的动态」 / 「输入关键词搜索该 UP 主的投稿视频」），**绝不预拉全量内容**。
3. **搜索执行与结果展示**：
   - 提交非空关键词后，对当前选中的 Tab 立即发起请求（另一个 Tab 在切过去时若当前关键词尚未检索过再懒加载或受并发池限制发起检索，避免同时触发两次高风控接口）。
   - **「视频」标签结果**：调用空间投稿搜索接口（`keyword` 参数），结果使用与空间主页「投稿」**完全一致的 `VideoCardUi` 双列视频卡片**渲染，支持分页加载与关键词高亮或空结果提示「未找到相关视频」。
   - **「动态」标签结果**：由于 B 站官方 Web 端空间动态流无可靠 `keyword` 参数，按顾问方案在本地做有界游标检索（串行最多拉取 5 页 / 至多 100 条最近动态，结果上限 20 条，总预算 9 秒），命中结果使用与空间主页「动态」**完全一致的 `SpaceDynamicCardUi` 卡片**渲染（同样无三点按钮、支持置顶标签/图文点击看大图/视频点击进详情/转发折叠块）。
   - 若检索触发上限（`limited = true`），在列表底部（或顶部轻提示条）克制说明「仅检索了最近动态，已显示匹配结果」。
   - 新搜索提交、切换关键词或销毁页面时递增 `searchGeneration` 并取消旧 `BiliRequest`，绝对杜绝旧请求覆盖新结果。

---

## 五、后端接口、数据模型与风控防御规划

1. **独立封装文件**：新建 `BiliSpaceApi.kt`（或集中管理空间接口、数据模型与短时内存缓存），避免继续膨胀 1580 行的 `BiliApi.kt`，同时共享 `BiliHttp`、`BiliWbiSign`、`BiliPlayUrl.cookieHeader`、`BiliSessionStore`、`LikeStateBridge`。
2. **接口映射清单**：
   - **空间基础资料**：`GET https://api.bilibili.com/x/space/wbi/acc/info`（WBI + Cookie + Referer `https://space.bilibili.com/$mid/` + `dm_*`），`-352/-401` 刷新一次 WBI 重试，仍失败则降级公开名片 `GET https://api.bilibili.com/x/web-interface/card?mid=$mid`。
   - **关注/粉丝数**：`GET https://api.bilibili.com/x/relation/stat?vmid=$mid`。
   - **获赞数**：`GET https://api.bilibili.com/x/space/upstat?mid=$mid`（仅登录有效且可为空，`null` 时展示 `--`）。
   - **关注状态与写关注**：优先读 `acc/info.is_followed` 与 `LikeStateBridge.resolveFollow`，未确定且已登录时补充 `GET https://api.bilibili.com/x/space/wbi/acc/relation?mid=$mid`；写关注复用 `BiliApi.modifyRelation(mid, follow)` 并在失败时按错误码（`-101` 未登录 / `22001` 不能关注自己 / `22002` 对方隐私限制 / `22013` 账号已注销）给出清晰可读提示。
   - **空间动态流**：`GET https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/space`（`host_mid`、`offset`、`timezone_offset=-480`、`platform=web`、`web_location=333.1387`、`features=...`），支持置顶标记（`module_tag.text == "置顶"` 等兼容）、`WORD`、`DRAW`、`OPUS`（新版图文）、`AV`、`FORWARD`（最多 1 层递归与失效兜底）及 `UNKNOWN` 只读降级。
   - **空间投稿 & 投稿搜索**：`GET https://api.bilibili.com/x/space/wbi/arc/search`（WBI + `mid`、`pn`、`ps=20`、`order=pubdate|click|stow`、`tid=0`、`keyword`、`platform=web`、`web_location=1550101`、`dm_*`），`-352` 刷一次 WBI 重试，再失败冷启动降级 `GET https://api.bilibili.com/x/series/recArchivesByKeywords`（结构兼容映射）。
   - **空间动态搜索**：基于 `feed/space` 游标串行拉取与本地文本过滤（正文 + opus 摘要 + 视频标题/简介 + 转发原动态文本），严格限制页数（最多 5 页）、命中条数（最多 20 条）、时间预算（9s）及游标死循环检测。
3. **公共组件抽离**：
   - 将 `VideoDetailActivity.kt` 内通用的图片全屏大图浏览（含 `ZoomableImageView` 的 `BitmapRegionDecoder` 长图优化、进度圈、保存相册）抽离为公共组件 `ImagePreviewDialog.kt`（保留 `VideoDetailActivity` 调用零回归），让空间页与空间搜索页动态九宫格大图直接享有同样流畅的长图查看体验。
   - 抽取可复用的 `SpaceDynamicCardUi.kt` 与 `SpaceHeaderView` 渲染，使 `SpaceActivity` 与 `SpaceSearchActivity` 动态展示 100% 一致。

---

## 六、单元测试规划

在 `app/src/test/kotlin/com/yuilittle/bili/SpaceApiParseTest.kt` 中覆盖纯逻辑与 JSON 解析（不依赖 Android 运行时类，防止单元测试触发 `ExceptionInInitializerError`）：
1. 空间资料解析：正常 WBI `acc/info`（等级 `0..6`、大会员 `vip.status==1` 文案优先级、普通用户隐藏大会员、`is_followed`）、`card` 降级解析、`upstat` 缺失/失败时 `likes == null`（不把 0 当缺失）。
2. 动态解析：置顶 `module_tag.text == "置顶"` 识别、纯文字 `WORD` + `rich_text_nodes`、老图文 `DRAW` 与新版 `OPUS` 多图提取、视频 `AV` 提取、转发 `FORWARD`（含原动态失效 `MAJOR_TYPE_NONE` / `orig == null`）、未知类型 `UNKNOWN` 安全降级与无三点菜单依赖。
3. 投稿分页与防死循环判断：`data.list.vlist` + `data.page(count/pn/ps)` `hasMore` 判定。
4. 动态关键词本地过滤匹配逻辑（含正文、视频标题与转发原文大小写不敏感匹配，及页数/条数上限截断 `limited = true`）。
5. 紧凑计数格式化与动态时间格式化（`刚刚` / `x分钟前` / `今天 HH:mm` / `MM-dd` / `yyyy-MM-dd`）。
