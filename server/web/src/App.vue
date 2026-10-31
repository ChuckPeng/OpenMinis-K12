<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "./api";

type Tab = "wecom" | "parents" | "devices" | "alerts" | "reports" | "j12" | "audit" | "status";

const view = ref<"setup" | "login" | "app">("login");
const tab = ref<Tab>("wecom");
const version = ref("");
const msg = ref("");
const err = ref("");

const setupUser = ref("admin");
const setupPass = ref("");
const loginUser = ref("admin");
const loginPass = ref("");

const appId = ref("");
const wCorp = ref("");
const wAgent = ref(0);
const wSecret = ref("");
const wToken = ref("");
const wAes = ref("");
const wApi = ref("");
const callbackUrl = ref("");

const parents = ref<Array<{ id: string; label: string; wecom_userid: string; can_approve: boolean }>>([]);
const pLabel = ref("");
const pUser = ref("");
const pApprove = ref(true);

const devices = ref<Array<{ id: string; child_id: string; child_label: string; label: string; status: string; last_seen?: string; app_version: string; config_version_applied: number }>>([]);
const pairCodeResult = ref("");

const alerts = ref<Array<{ id: string; level: string; category: string; occurred_at: number; has_snippet: boolean; acknowledged_by?: string; created_at: string }>>([]);
const activeSnippet = ref("");
const activeSnippetId = ref("");

const reports = ref<Array<{ id: string; child_id: string; kind: string; period_start: string; metrics_json: any; parent_text_json: any; text_source: string; generated_at: string }>>([]);
const generatedLink = ref("");
const selectedChildId = ref("");

const lastTask = ref("");
const j12Code = ref("");
const j12From = ref("");
const j12Key = ref("approve_question");

const audit = ref<Array<{ actor: string; action: string; target: string; at: string }>>([]);
const statusText = ref("");

async function boot() {
  err.value = "";
  const sess = await api<{ csrf_token: string; version: string }>("/api/v1/admin/session");
  if (sess.status === 401) {
    const probe = await api("/api/v1/admin/setup", { method: "POST", body: "{}" });
    view.value = probe.status === 409 ? "login" : "setup";
    return;
  }
  version.value = sess.body?.version || "";
  view.value = "app";
  await loadWecom();
}

async function doSetup() {
  const r = await api("/api/v1/admin/setup", {
    method: "POST",
    body: JSON.stringify({ username: setupUser.value, password: setupPass.value }),
  });
  if (!r.ok) {
    err.value = (r.body as { message?: string })?.message || "失败";
    return;
  }
  await boot();
}

async function doLogin() {
  const r = await api("/api/v1/admin/login", {
    method: "POST",
    body: JSON.stringify({ username: loginUser.value, password: loginPass.value }),
  });
  if (!r.ok) {
    err.value = (r.body as { message?: string })?.message || "失败";
    return;
  }
  await boot();
}

async function doLogout() {
  await api("/api/v1/admin/logout", { method: "POST", body: "{}" });
  location.reload();
}

async function loadWecom() {
  const r = await api<Array<Record<string, unknown>>>("/api/v1/admin/wecom-apps");
  if (!r.body?.length) {
    callbackUrl.value = "尚未保存应用";
    return;
  }
  const a = r.body[0];
  appId.value = String(a.id || "");
  wCorp.value = String(a.corpid || "");
  wAgent.value = Number(a.agentid || 0);
  wToken.value = String(a.token || "");
  wApi.value = String(a.api_base_url || "");
  callbackUrl.value = "回调 URL: " + String(a.callback_url || "(先配置 public_base_url)");
}

async function saveWecom() {
  const r = await api("/api/v1/admin/wecom-apps", {
    method: "PUT",
    body: JSON.stringify({
      id: appId.value,
      corpid: wCorp.value,
      agentid: wAgent.value,
      secret: wSecret.value,
      token: wToken.value,
      encoding_aes_key: wAes.value,
      api_base_url: wApi.value,
    }),
  });
  if (r.ok) {
    msg.value = "已保存";
    await loadWecom();
  } else {
    msg.value = "保存失败";
  }
}

async function testToken() {
  msg.value = "测试中…";
  const r = await api(`/api/v1/admin/wecom-apps/${appId.value}/test-token`, { method: "POST", body: "{}" });
  msg.value = r.ok ? "gettoken 成功" : "gettoken 失败: " + JSON.stringify(r.body);
}

async function testSend() {
  msg.value = "发送中…";
  const r = await api(`/api/v1/admin/wecom-apps/${appId.value}/test-send`, { method: "POST", body: "{}" });
  msg.value = r.ok ? "已入队 outbox" : "入队失败: " + JSON.stringify(r.body);
}

async function loadParents() {
  const r = await api<Array<{ id: string; label: string; wecom_userid: string; can_approve: boolean }>>("/api/v1/admin/parents");
  parents.value = r.body || [];
}

async function addParent() {
  if (!pLabel.value || !pUser.value) return;
  await api("/api/v1/admin/parents", {
    method: "POST",
    body: JSON.stringify({ label: pLabel.value, wecom_userid: pUser.value, can_approve: pApprove.value }),
  });
  pLabel.value = "";
  pUser.value = "";
  await loadParents();
}

async function loadDevices() {
  const r = await api<Array<{ id: string; child_id: string; child_label: string; label: string; status: string; last_seen?: string; app_version: string; config_version_applied: number }>>("/api/v1/admin/devices");
  devices.value = r.body || [];
  if (devices.value.length > 0 && !selectedChildId.value) {
    selectedChildId.value = devices.value[0].child_id;
  }
}

async function generatePairCode() {
  const r = await api<{ pair_code: string; expires_at: string; qr_payload: string }>("/api/v1/admin/pair-codes", { method: "POST", body: "{}" });
  if (r.ok && r.body) {
    pairCodeResult.value = `配对码：${r.body.pair_code}（10分钟内有效）\n载荷：${r.body.qr_payload}`;
  } else {
    pairCodeResult.value = "生成失败";
  }
}

async function revokeDevice(id: string) {
  if (!confirm("确定要吊销该设备吗？该平板将立即失去访问权限。")) return;
  await api(`/api/v1/admin/devices/${id}/revoke`, { method: "POST", body: "{}" });
  await loadDevices();
}

async function approveDevice(id: string) {
  await api(`/api/v1/admin/devices/${id}/approve`, { method: "POST", body: "{}" });
  await loadDevices();
}

async function loadAlerts() {
  const r = await api<Array<{ id: string; level: string; category: string; occurred_at: number; has_snippet: boolean; acknowledged_by?: string; created_at: string }>>("/api/v1/admin/alerts");
  alerts.value = r.body || [];
}

async function viewSnippet(id: string) {
  activeSnippetId.value = id;
  const r = await api<{ snippet: string }>(`/api/v1/admin/alerts/${id}/snippet`);
  if (r.ok && r.body) {
    activeSnippet.value = r.body.snippet || "（无关联文本片段）";
  } else {
    activeSnippet.value = "加载片段失败";
  }
}

async function ackAlert(id: string) {
  await api(`/api/v1/admin/alerts/${id}/ack`, { method: "POST", body: "{}" });
  await loadAlerts();
}

async function loadReports() {
  const r = await api<Array<{ id: string; child_id: string; kind: string; period_start: string; metrics_json: any; parent_text_json: any; text_source: string; generated_at: string }>>("/api/v1/admin/reports");
  reports.value = r.body || [];
}

async function generateDailyReport() {
  if (!selectedChildId.value) {
    err.value = "请先选择孩子或配对设备";
    return;
  }
  const r = await api<{ report_id: string; signed_url: string; summary: string }>("/api/v1/admin/reports/generate", {
    method: "POST",
    body: JSON.stringify({ child_id: selectedChildId.value }),
  });
  if (r.ok && r.body) {
    generatedLink.value = `简报链接（72小时有效）：${r.body.signed_url}\n摘要：${r.body.summary}`;
    await loadReports();
  } else {
    err.value = "生成简报失败";
  }
}

async function createFallback() {
  const r = await api<{ request_id: string; response_code: string }>("/api/v1/admin/j12/fallback-code", {
    method: "POST",
    body: "{}",
  });
  if (r.ok && r.body) {
    lastTask.value = r.body.request_id;
    j12Code.value = `备用码: ${r.body.response_code} (申请: ${r.body.request_id})`;
  }
}

async function simulate() {
  const r = await api("/api/v1/admin/j12/simulate", {
    method: "POST",
    body: JSON.stringify({
      from_user: j12From.value || (parents.value[0]?.wecom_userid || "user1"),
      task_id: lastTask.value,
      event_key: j12Key.value,
    }),
  });
  msg.value = r.ok ? "已处理" : "失败";
}

async function markButton(ok: boolean) {
  await api(`/api/v1/admin/wecom-apps/${appId.value}/mark-button`, {
    method: "POST",
    body: JSON.stringify({ ok }),
  });
  msg.value = ok ? "已标记: 按钮可达 (J12)" : "已标记: 按钮不可达, 走备用码 (J14)";
}

async function loadAudit() {
  const r = await api<Array<{ Actor?: string; actor?: string; Action?: string; action?: string; Target?: string; target?: string; At?: string; at?: string }>>("/api/v1/admin/audit");
  audit.value = (r.body || []).map((row) => ({
    actor: row.Actor || row.actor || "",
    action: row.Action || row.action || "",
    target: row.Target || row.target || "",
    at: row.At || row.at || "",
  }));
}

async function loadStatus() {
  const r = await api("/api/v1/admin/status");
  statusText.value = JSON.stringify(r.body, null, 2);
}

function switchTab(t: Tab) {
  tab.value = t;
  msg.value = "";
  err.value = "";
  if (t === "parents") loadParents();
  if (t === "devices") loadDevices();
  if (t === "alerts") loadAlerts();
  if (t === "reports") {
    loadDevices();
    loadReports();
  }
  if (t === "audit") loadAudit();
  if (t === "status") loadStatus();
  if (t === "wecom") loadWecom();
}

onMounted(boot);
</script>

<template>
  <p class="brand">伴学 Minis · OpenMinis-K12 服务端</p>
  <h1>管理界面</h1>
  <p class="sub">向导 · 企微 · 设备 · 告警 · 简报 · 审计（M5b）</p>

  <section v-if="view === 'setup'" class="panel">
    <h2>安装向导 · 管理员口令</h2>
    <label>用户名</label>
    <input v-model="setupUser" />
    <label>口令（≥12，含字母与数字）</label>
    <input v-model="setupPass" type="password" autocomplete="new-password" />
    <button @click="doSetup">创建并登录</button>
    <p v-if="err" class="err">{{ err }}</p>
  </section>

  <section v-else-if="view === 'login'" class="panel">
    <h2>登录</h2>
    <label>用户名</label>
    <input v-model="loginUser" />
    <label>口令</label>
    <input v-model="loginPass" type="password" autocomplete="current-password" />
    <button @click="doLogin">登录</button>
    <p v-if="err" class="err">{{ err }}</p>
  </section>

  <div v-else>
    <nav>
      <button class="secondary" @click="switchTab('wecom')">企微</button>
      <button class="secondary" @click="switchTab('parents')">家长</button>
      <button class="secondary" @click="switchTab('devices')">设备配对</button>
      <button class="secondary" @click="switchTab('alerts')">安全告警</button>
      <button class="secondary" @click="switchTab('reports')">学情日简报</button>
      <button class="secondary" @click="switchTab('j12')">J12 实测</button>
      <button class="secondary" @click="switchTab('audit')">审计</button>
      <button class="secondary" @click="switchTab('status')">状态</button>
      <button class="ghost" @click="doLogout">退出</button>
    </nav>

    <section v-if="tab === 'wecom'" class="panel">
      <h2>企业微信应用</h2>
      <p class="sub">Secret / EncodingAESKey 只写不回显。回调 URL 由 public_base_url 拼接。</p>
      <div class="row">
        <div>
          <label>CorpID</label>
          <input v-model="wCorp" />
        </div>
        <div>
          <label>AgentId</label>
          <input v-model.number="wAgent" type="number" />
        </div>
      </div>
      <label>Secret（留空表示不修改）</label>
      <input v-model="wSecret" type="password" autocomplete="off" />
      <label>Token</label>
      <input v-model="wToken" />
      <label>EncodingAESKey（43 字符）</label>
      <input v-model="wAes" autocomplete="off" />
      <label>API 基址（出站转发代理）</label>
      <input v-model="wApi" placeholder="https://qyapi.weixin.qq.com" />
      <button @click="saveWecom">保存</button>
      <button class="secondary" @click="testToken">测试 gettoken</button>
      <button class="secondary" @click="testSend">测试发送</button>
      <p class="mono">{{ callbackUrl }}</p>
      <p :class="msg.includes('失败') ? 'err' : 'ok'">{{ msg }}</p>
    </section>

    <section v-else-if="tab === 'parents'" class="panel">
      <h2>可审批家长</h2>
      <div class="row">
        <div>
          <label>标签</label>
          <input v-model="pLabel" placeholder="家长A" />
        </div>
        <div>
          <label>企微 UserId</label>
          <input v-model="pUser" />
        </div>
      </div>
      <label><input v-model="pApprove" type="checkbox" /> 可审批</label>
      <button @click="addParent">添加</button>
      <table>
        <thead>
          <tr>
            <th>标签</th>
            <th>UserId</th>
            <th>可审批</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in parents" :key="p.id">
            <td>{{ p.label }}</td>
            <td class="mono">{{ p.wecom_userid }}</td>
            <td>{{ p.can_approve ? "是" : "否" }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else-if="tab === 'devices'" class="panel">
      <h2>设备管理与配对 (M5b)</h2>
      <p class="sub">为新平板生成 10 分钟一次性配对码。设备注册后家长在企微确认生效。</p>
      <button @click="generatePairCode">生成 10 分钟配对码</button>
      <pre v-if="pairCodeResult" class="mono">{{ pairCodeResult }}</pre>

      <h3 style="margin-top: 24px;">已登记设备</h3>
      <table>
        <thead>
          <tr>
            <th>设备标签</th>
            <th>孩子</th>
            <th>状态</th>
            <th>App 版本</th>
            <th>最后心跳</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in devices" :key="d.id">
            <td>{{ d.label }}</td>
            <td>{{ d.child_label }}</td>
            <td>
              <span :style="{ color: d.status === 'active' ? '#22c55e' : (d.status === 'revoked' ? '#ef4444' : '#f59e0b') }">
                {{ d.status === 'active' ? '正常在用' : (d.status === 'revoked' ? '已吊销' : '待家长确认') }}
              </span>
            </td>
            <td>{{ d.app_version || '未回报' }}</td>
            <td>{{ d.last_seen || '未上线' }}</td>
            <td>
              <button v-if="d.status === 'pending_confirm'" class="secondary" @click="approveDevice(d.id)">确认绑定</button>
              <button v-if="d.status !== 'revoked'" class="ghost" @click="revokeDevice(d.id)">吊销</button>
            </td>
          </tr>
          <tr v-if="devices.length === 0">
            <td colspan="6" style="text-align: center; color: #999;">暂无设备</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else-if="tab === 'alerts'" class="panel">
      <h2>安全与情绪告警 (M5b)</h2>
      <p class="sub">企微通知不含敏感原文片段。密文加密保存 30 天，管理员在网页查看计入审计日志。</p>
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>级别</th>
            <th>类别</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in alerts" :key="a.id">
            <td>{{ new Date(a.occurred_at).toLocaleString() }}</td>
            <td>
              <strong :style="{ color: a.level === '紧急' ? '#ef4444' : (a.level === '关注' ? '#f59e0b' : '#3b82f6') }">
                {{ a.level }}
              </strong>
            </td>
            <td>{{ a.category }}</td>
            <td>{{ a.acknowledged_by ? `已由 ${a.acknowledged_by} 确认` : '待确认' }}</td>
            <td>
              <button v-if="a.has_snippet" class="secondary" @click="viewSnippet(a.id)">查看片段</button>
              <button v-if="!a.acknowledged_by" class="ghost" @click="ackAlert(a.id)">确认知晓</button>
            </td>
          </tr>
          <tr v-if="alerts.length === 0">
            <td colspan="5" style="text-align: center; color: #999;">暂无安全告警记录</td>
          </tr>
        </tbody>
      </table>

      <div v-if="activeSnippet" class="summary" style="margin-top: 16px;">
        <strong>触发片段（前后各至多2句）：</strong>
        <p>{{ activeSnippet }}</p>
        <button class="ghost" @click="activeSnippet = ''">关闭</button>
      </div>
    </section>

    <section v-else-if="tab === 'reports'" class="panel">
      <h2>学情日简报 (M5b)</h2>
      <p class="sub">日界 04:00 (Asia/Shanghai)。生成日简报模板，带 72 小时 HMAC 防篡改签名链接。</p>
      <div class="row">
        <div>
          <label>选择孩子</label>
          <select v-model="selectedChildId">
            <option v-for="d in devices" :key="d.child_id" :value="d.child_id">{{ d.child_label }} ({{ d.child_id }})</option>
          </select>
        </div>
      </div>
      <button @click="generateDailyReport">生成今日日简报</button>
      <pre v-if="generatedLink" class="mono">{{ generatedLink }}</pre>

      <h3 style="margin-top: 24px;">历史报告</h3>
      <table>
        <thead>
          <tr>
            <th>日期</th>
            <th>类型</th>
            <th>生成时间</th>
            <th>文字来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rep in reports" :key="rep.id">
            <td>{{ rep.period_start }}</td>
            <td>{{ rep.kind === 'daily' ? '日简报' : rep.kind }}</td>
            <td>{{ rep.generated_at }}</td>
            <td>{{ rep.text_source }}</td>
          </tr>
          <tr v-if="reports.length === 0">
            <td colspan="4" style="text-align: center; color: #999;">暂无报告</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else-if="tab === 'j12'" class="panel">
      <h2>J12 按钮回调实测 / 备用码</h2>
      <p class="sub">CI 无法触达所有者企微。创建模拟申请后，用管理 API 模拟回调，或用 6 位备用码批准。</p>
      <button @click="createFallback">创建解锁申请（含备用码）</button>
      <p class="mono">{{ j12Code }}</p>
      <label>模拟回调 From UserId</label>
      <input v-model="j12From" />
      <label>EventKey</label>
      <select v-model="j12Key">
        <option value="approve_question">approve_question</option>
        <option value="unlock_15">unlock_15</option>
        <option value="deny">deny</option>
      </select>
      <button @click="simulate">模拟 template_card_event</button>
      <label>标记按钮回调结果（J14）</label>
      <div>
        <button class="secondary" @click="markButton(true)">到达</button>
        <button class="ghost" @click="markButton(false)">未到达 → 走备用码</button>
      </div>
      <p :class="msg.includes('失败') ? 'err' : 'ok'">{{ msg }}</p>
    </section>

    <section v-else-if="tab === 'audit'" class="panel">
      <h2>审计</h2>
      <table>
        <thead>
          <tr>
            <th>时间</th>
            <th>主体</th>
            <th>动作</th>
            <th>对象</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in audit" :key="i">
            <td>{{ r.at }}</td>
            <td>{{ r.actor }}</td>
            <td>{{ r.action }}</td>
            <td>{{ r.target }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-else class="panel">
      <h2>系统状态</h2>
      <pre class="mono">{{ statusText }}</pre>
    </section>
  </div>

  <footer>{{ version }}</footer>
</template>
