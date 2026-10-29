<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api } from "./api";

type Tab = "wecom" | "parents" | "j12" | "audit" | "status";

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
  if (sess.body?.csrf_token) {
    /* csrf stored in api module */
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
      id: appId.value || undefined,
      corpid: wCorp.value,
      agentid: wAgent.value,
      token: wToken.value,
      api_base_url: wApi.value,
      secret: wSecret.value,
      encoding_aes_key: wAes.value,
      enabled: true,
    }),
  });
  msg.value = r.ok ? "已保存" : (r.body as { message?: string })?.message || "失败";
  wSecret.value = "";
  wAes.value = "";
  await loadWecom();
}

async function testToken() {
  if (!appId.value) return;
  const r = await api<{ ok: boolean; message: string }>(
    `/api/v1/admin/wecom-apps/${appId.value}/test-token`,
    { method: "POST", body: "{}" },
  );
  msg.value = r.body?.ok ? "gettoken 成功（token 不回显）" : r.body?.message || "失败";
}

async function testSend() {
  if (!appId.value) return;
  const r = await api<{ message: string }>(`/api/v1/admin/wecom-apps/${appId.value}/test-send`, {
    method: "POST",
    body: JSON.stringify({ kind: "text" }),
  });
  msg.value = r.body?.message || "";
}

async function loadParents() {
  const r = await api<typeof parents.value>("/api/v1/admin/parents");
  parents.value = r.body || [];
}

async function addParent() {
  await api("/api/v1/admin/parents", {
    method: "POST",
    body: JSON.stringify({
      label: pLabel.value,
      wecom_userid: pUser.value,
      can_approve: pApprove.value,
      channel: "wecom_app",
    }),
  });
  pLabel.value = "";
  pUser.value = "";
  await loadParents();
}

async function createFallback() {
  const r = await api<{ id: string; task_id: string; response_code: string; use_buttons: boolean }>(
    "/api/v1/admin/j12/fallback-code",
    { method: "POST", body: JSON.stringify({ subject: "数学" }) },
  );
  if (!r.ok || !r.body) {
    err.value = "创建失败";
    return;
  }
  lastTask.value = r.body.task_id;
  j12Code.value = `request=${r.body.id} task=${r.body.task_id} 备用码=${r.body.response_code} use_buttons=${r.body.use_buttons}`;
  msg.value = "已入 outbox；可模拟回调或把备用码告诉孩子。";
}

async function simulate() {
  const r = await api("/api/v1/admin/j12/simulate", {
    method: "POST",
    body: JSON.stringify({
      app_id: appId.value,
      from_userid: j12From.value,
      event_key: j12Key.value,
      task_id: lastTask.value,
    }),
  });
  msg.value = r.ok ? "模拟回调已处理" : (r.body as { message?: string })?.message || "失败";
}

async function markButton(ok: boolean) {
  if (!appId.value) return;
  await api(`/api/v1/admin/wecom-apps/${appId.value}/mark-button-callback`, {
    method: "POST",
    body: JSON.stringify({ ok }),
  });
  msg.value = ok ? "已记录：按钮回调可达" : "已记录：按钮回调不可达，产品走文本+备用码";
}

async function loadAudit() {
  const r = await api<Array<Record<string, string>>>("/api/v1/admin/audit");
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
  if (t === "audit") loadAudit();
  if (t === "status") loadStatus();
  if (t === "wecom") loadWecom();
}

onMounted(boot);
</script>

<template>
  <p class="brand">伴学 Minis · OpenMinis-K12 服务端</p>
  <h1>管理界面</h1>
  <p class="sub">向导 · 企微 · 审计（M5a）</p>

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
