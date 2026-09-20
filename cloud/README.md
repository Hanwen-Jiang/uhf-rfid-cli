# cloud/ —— 云端标签库（Cloudflare Worker + D1）

给手机 App 用的标签库后端。**已经部署好了**：

```
Worker 地址 : https://rfid-tags.root-60a.workers.dev
D1 数据库   : rfid-tags  (id 32e9b21d-6287-4166-bc23-cb5398d6710b, 区域 WNAM)
账号        : root@nsnzd.com
```

## 接口

下面前 4 个要求带 `Authorization: Bearer <API_KEY>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 存活探测，不用 key |
| GET | `/api/check?epc=&tid=` | 只做完整性校验，不入库（调试用） |
| GET | `/api/tags` | 列表；`?unnamed=1` 只看没命名的，`?limit=N` |
| GET | `/api/tags?epc=X` | 查这一颗在不在库里。**`&touch=1`** = 这次确实读到了，读到次数 +1 |
| POST | `/api/tags` | 上传 `{epc, tid, status?, anomalyNote?, operator?}` |
| POST | `/api/tags/written` | 报告「写成功了一次」`{epc}` |
| PATCH | `/api/tags/:id` | 命名 `{name}` |
| DELETE | `/api/tags/:id` | 删除 |
| GET | `/api/anomalies` | 异常记录 |

上传同一颗标签（EPC 相同）走**更新**而不是新增 —— 不然反复读同一颗会把库撑爆。
重复上传时 `operator` 用 `COALESCE` 保留原值，不会被空值抹掉。

### 两个计数

| 字段 | 含义 | 谁 +1 |
|---|---|---|
| `seen_count` / `last_seen` | 读到过几次 / 最后读到 | App 查询时带 `touch=1` |
| `write_count` / `last_written` | 写成功几次 / 最后写入 | App 调 `/api/tags/written`，**两个区都写成功才调** |

老字段 `reads` 是"历史上传次数"，保留兼容但界面不再显示 —— 命中不再重复上传之后
它不会再涨，显示出来只会误导。

### 批量导入

```bash
python cloud/import_tags.py              # 用脚本里的内置列表
python cloud/import_tags.py tags.csv     # CSV：每行 名称,EPC,TID
```

幂等（同一颗重复跑是更新），并且逐条报告完整性校验结果；不合格的按异常入库，
不会伪装成正常。

## 完整性规则（{完整}）

这颗标签的 EPC 和 TID **只差第 9 个 hex 位**：EPC 那一位是 `0`，TID 那一位是 `2`，其余完全相同。

```
EPC  E2806915 0 000502008B25C75
TID  E2806915 2 000502008B25C75
            ↑ 第 9 位
```

规则实现在两处，**必须保持一致**：
- 服务端 `cloud/src/index.js` 的 `checkComplete()`
- 客户端 `android/.../TagValidator.java`

服务端的判定是权威的：客户端说"正常"但服务端查出不合格，会**按异常入库并记一条 `anomalies`**，不会悄悄放过。

## 密钥

`API_KEY` 是 Worker 的 secret（`wrangler secret put API_KEY`），当前值在 `Z:\rfid\.tools\cf-key.txt`，
App 里在「设置」界面填。

> 客户端请求**必须带正常的 User-Agent**。实测用 Python 默认的 `Python-urllib/3.x`
> 会被 Cloudflare 直接 403；换成 `uhf-rfid-cli/1.0` 就正常了。

## 自己重新部署

```bash
cd cloud
wrangler login
wrangler d1 create rfid-tags                                  # 把 id 填进 wrangler.toml
wrangler d1 execute rfid-tags --remote --file=schema.sql
wrangler secret put API_KEY                                   # 起一个长随机串
wrangler deploy
```

本地调试：

```bash
wrangler d1 execute rfid-tags --local --file=schema.sql
wrangler dev
```

## 表结构

- `tags` —— `epc`（唯一）、`tid`、`name`（未命名为 NULL）、`status`（`ok`/`anomaly`）、
  `anomaly_note`、`operator`（谁传的）、`reads`、时间戳
- `anomalies` —— 异常审计流水，单独一张表，不会被后续更新覆盖

`operator` 就是用来区分几个人共用时"谁传的"。

## 设计取舍

- **异常数据照样入库**，只是打上 `status='anomaly'` 并记一条流水。连续两次读到同样的
  数据本身就是有价值的信息，丢掉不如留着，但要能一眼看出它不合格。
- **不做账号体系**，一个共享 key + `operator` 字段。要真正的多用户隔离得换 Cloudflare
  Access 或自建认证，那是另一件事。
