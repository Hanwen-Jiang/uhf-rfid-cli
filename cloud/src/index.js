/**
 * UHF RFID 标签库 —— Cloudflare Worker + D1
 *
 * 给手机 App（以及电脑端工具）提供标签库的读写接口。
 *
 * 接口一览（都要求 Authorization: Bearer <API_KEY>）：
 *   GET    /api/tags                 列表。可选 ?unnamed=1 只看没命名的
 *   POST   /api/tags                 上传一颗标签 {epc, tid, status?, anomalyNote?}
 *   PATCH  /api/tags/:id             命名 {name}
 *   DELETE /api/tags/:id             删除
 *   GET    /api/anomalies            异常记录
 *   GET    /api/check?epc=&tid=      只做完整性校验，不入库（调试用）
 *   GET    /health                   存活探测（不需要 key）
 *
 * 完整性规则（{完整}）：这颗标签的 EPC 和 TID 只差第 9 个 hex 位 ——
 * EPC 那一位是 '0'，TID 那一位是 '2'，其余完全相同。例：
 *     EPC  E2806915 0 000502008B25C75
 *     TID  E2806915 2 000502008B25C75
 *                 ↑ 第 9 位
 * 不满足就是不合格，但**数据本身照样收**（带 anomaly 标记），
 * 因为连续两次读到同样数据的标签，比"没上传"更有价值。
 */

const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' };

/** 第 9 个 hex 位（0 基是 8）上，EPC 应为 '0'、TID 应为 '2'。 */
const DIFF_INDEX = 8;
const EPC_AT_DIFF = '0';
const TID_AT_DIFF = '2';

function json(body, status = 200) {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: JSON_HEADERS,
  });
}

/** 规范化：去空格、转大写、校验是不是 hex。返回 null 表示不合法。 */
function normHex(value) {
  if (typeof value !== 'string') return null;
  const s = value.replace(/[\s:,-]/g, '').toUpperCase();
  if (!s || s.length % 2 !== 0 || !/^[0-9A-F]+$/.test(s)) return null;
  return s;
}

/**
 * 完整性校验。
 * @returns {{complete: boolean, reason: string, detail?: string}}
 */
function checkComplete(epc, tid) {
  if (epc.length !== tid.length) {
    return {
      complete: false,
      reason: 'length_mismatch',
      detail: `EPC ${epc.length} 字符，TID ${tid.length} 字符`,
    };
  }
  if (epc.length <= DIFF_INDEX) {
    return { complete: false, reason: 'too_short', detail: `只有 ${epc.length} 个字符` };
  }
  const e = epc[DIFF_INDEX];
  const t = tid[DIFF_INDEX];
  if (e !== EPC_AT_DIFF || t !== TID_AT_DIFF) {
    return {
      complete: false,
      reason: 'ninth_digit',
      detail: `第 ${DIFF_INDEX + 1} 位应为 EPC='${EPC_AT_DIFF}' / TID='${TID_AT_DIFF}'，`
        + `实际 EPC='${e}' / TID='${t}'`,
    };
  }
  // 其余位必须完全一致
  for (let i = 0; i < epc.length; i++) {
    if (i === DIFF_INDEX) continue;
    if (epc[i] !== tid[i]) {
      return {
        complete: false,
        reason: 'other_digit',
        detail: `第 ${i + 1} 位就不同：EPC='${epc[i]}' / TID='${tid[i]}'`,
      };
    }
  }
  return { complete: true, reason: 'ok' };
}

/** EPC 和 TID 共同的后缀（除了第 9 位以外它们是一样的）。 */
function commonSuffix(epc, tid, len = 5) {
  const n = Math.min(epc.length, tid.length, len);
  return epc.slice(-n);
}

function rowToTag(row) {
  return {
    id: row.id,
    epc: row.epc,
    tid: row.tid,
    name: row.name,
    status: row.status,
    anomalyNote: row.anomaly_note,
    operator: row.operator,
    reads: row.reads,
    suffix: commonSuffix(row.epc, row.tid, 5),
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

/** 鉴权。API_KEY 通过 `wrangler secret put API_KEY` 设置。 */
function authorized(request, env) {
  if (!env.API_KEY) return false;      // 没设 key 就一律拒绝，避免裸奔
  const h = request.headers.get('authorization') || '';
  const token = h.replace(/^Bearer\s+/i, '').trim();
  // 长度先比一下，避免极早返回泄露信息
  if (token.length !== env.API_KEY.length) return false;
  // 定时安全比较
  let diff = 0;
  for (let i = 0; i < token.length; i++) {
    diff |= token.charCodeAt(i) ^ env.API_KEY.charCodeAt(i);
  }
  return diff === 0;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    if (path === '/health') {
      const row = await env.DB.prepare('SELECT COUNT(*) AS n FROM tags').first();
      return json({ ok: true, tags: row ? row.n : 0 });
    }

    if (!authorized(request, env)) {
      return json({ error: 'unauthorized', hint: '需要 Authorization: Bearer <API_KEY>' }, 401);
    }

    try {
      // ---------------------------------------------------------- 校验（不入库）
      if (path === '/api/check' && request.method === 'GET') {
        const epc = normHex(url.searchParams.get('epc'));
        const tid = normHex(url.searchParams.get('tid'));
        if (!epc || !tid) return json({ error: 'bad_epc_or_tid' }, 400);
        const v = checkComplete(epc, tid);
        return json({ epc, tid, suffix: commonSuffix(epc, tid, 5), ...v });
      }

      // ---------------------------------------------------------- 列表
      if (path === '/api/tags' && request.method === 'GET') {
        const unnamed = url.searchParams.get('unnamed') === '1';
        const limit = Math.min(parseInt(url.searchParams.get('limit') || '500', 10) || 500, 2000);
        const sql = unnamed
          ? 'SELECT * FROM tags WHERE name IS NULL OR name = \'\' ORDER BY created_at DESC LIMIT ?'
          : 'SELECT * FROM tags ORDER BY (name IS NULL) DESC, created_at DESC LIMIT ?';
        const { results } = await env.DB.prepare(sql).bind(limit).all();
        return json({ count: results.length, tags: results.map(rowToTag) });
      }

      // ---------------------------------------------------------- 上传
      if (path === '/api/tags' && request.method === 'POST') {
        const body = await request.json().catch(() => null);
        if (!body) return json({ error: 'bad_json' }, 400);

        const epc = normHex(body.epc);
        const tid = normHex(body.tid);
        if (!epc || !tid) return json({ error: 'bad_epc_or_tid' }, 400);

        const verdict = checkComplete(epc, tid);
        // 客户端说是异常就是异常（它可能已经做过"连续两次相同"的判断）；
        // 客户端说是正常但我们查出不合格 -> 以服务端为准并记异常。
        const clientSaysAnomaly = body.status === 'anomaly';
        const status = (verdict.complete && !clientSaysAnomaly) ? 'ok' : 'anomaly';
        const note = status === 'anomaly'
          ? (body.anomalyNote || verdict.detail || verdict.reason)
          : null;
        const operator = (body.operator || '').toString().slice(0, 64) || null;

        const existing = await env.DB
          .prepare('SELECT * FROM tags WHERE epc = ?').bind(epc).first();

        let row;
        if (existing) {
          await env.DB.prepare(
            `UPDATE tags SET tid = ?, status = ?, anomaly_note = ?, operator = ?,
                             reads = reads + 1, updated_at = datetime('now')
             WHERE epc = ?`
          ).bind(tid, status, note, operator, epc).run();
          row = await env.DB.prepare('SELECT * FROM tags WHERE epc = ?').bind(epc).first();
        } else {
          const res = await env.DB.prepare(
            `INSERT INTO tags (epc, tid, name, status, anomaly_note, operator)
             VALUES (?, ?, NULL, ?, ?, ?)`
          ).bind(epc, tid, status, note, operator).run();
          row = await env.DB
            .prepare('SELECT * FROM tags WHERE id = ?')
            .bind(res.meta.last_row_id).first();
        }

        if (status === 'anomaly') {
          await env.DB.prepare(
            `INSERT INTO anomalies (epc, tid, reason, detail, operator)
             VALUES (?, ?, ?, ?, ?)`
          ).bind(epc, tid, clientSaysAnomaly ? 'client_reported' : verdict.reason,
                 note, operator).run();
        }

        return json({
          tag: rowToTag(row),
          verdict,
          created: !existing,
          status,
        }, existing ? 200 : 201);
      }

      // ---------------------------------------------------------- 命名 / 删除
      const m = path.match(/^\/api\/tags\/(\d+)$/);
      if (m) {
        const id = parseInt(m[1], 10);
        if (request.method === 'PATCH') {
          const body = await request.json().catch(() => null);
          if (!body || typeof body.name !== 'string') return json({ error: 'bad_name' }, 400);
          const name = body.name.trim().slice(0, 128);
          await env.DB.prepare(
            `UPDATE tags SET name = ?, updated_at = datetime('now') WHERE id = ?`
          ).bind(name || null, id).run();
          const row = await env.DB.prepare('SELECT * FROM tags WHERE id = ?').bind(id).first();
          if (!row) return json({ error: 'not_found' }, 404);
          return json({ tag: rowToTag(row) });
        }
        if (request.method === 'DELETE') {
          const res = await env.DB.prepare('DELETE FROM tags WHERE id = ?').bind(id).run();
          if (!res.meta.changes) return json({ error: 'not_found' }, 404);
          return json({ deleted: id });
        }
      }

      // ---------------------------------------------------------- 异常记录
      if (path === '/api/anomalies' && request.method === 'GET') {
        const limit = Math.min(parseInt(url.searchParams.get('limit') || '200', 10) || 200, 1000);
        const { results } = await env.DB
          .prepare('SELECT * FROM anomalies ORDER BY created_at DESC LIMIT ?')
          .bind(limit).all();
        return json({ count: results.length, anomalies: results });
      }

      return json({ error: 'not_found', path, method: request.method }, 404);
    } catch (e) {
      return json({ error: 'server_error', message: String(e && e.message || e) }, 500);
    }
  },
};

// 导出给单元测试用
export { checkComplete, normHex, commonSuffix };
