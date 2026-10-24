<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, csrf, session, logout } from './api'
import { today, fmt, syncTime, errorText, roomPhoto } from './time'
import BookView from './views/BookView.vue'
import AdminView from './views/AdminView.vue'
import RecordsView from './views/RecordsView.vue'
import EditorDialog from './components/EditorDialog.vue'
import ReservationDetail from './components/ReservationDetail.vue'
import RecordStatus from './components/RecordStatus.vue'
const route = useRoute(),
  router = useRouter(),
  boot = ref(null),
  loading = ref(true),
  error = ref(''),
  loginError = ref(''),
  loginBusy = ref(false),
  loginForm = reactive({ account: '', password: '' }),
  summary = ref(),
  editor = ref(null),
  detailId = ref(null),
  viewRef = ref(),
  stats = ref(null),
  statsError = ref(''),
  statsBusy = ref(false),
  range = reactive({ start: '', end: '' })
const admin = computed(() => session.user?.role === 'ADMIN'),
  view = computed(() => route.path.slice(1) || 'home')
const studentNav = [
  ['home', '我的工作台'],
  ['book', '预约座位'],
  ['reservations', '我的预约'],
  ['violations', '违约记录'],
  ['notices', '公告与规则'],
  ['profile', '个人信息']
]
const adminNav = [
  ['home', '管理概览'],
  ['rooms', '阅览室管理'],
  ['seats', '座位管理'],
  ['reservations', '预约管理'],
  ['users', '学生管理'],
  ['violations', '违约管理'],
  ['notices', '公告管理'],
  ['rules', '预约规则'],
  ['audits', '操作日志'],
  ['profile', '个人信息']
]
const navigation = computed(() => (admin.value ? adminNav : studentNav)),
  title = computed(() => navigation.value.find((n) => n[0] === view.value)?.[1] || '工作台')
let statsController,
  statsVersion = 0
async function refresh() {
  boot.value = await api('/bootstrap')
  syncTime(boot.value.serverTime)
  session.user = boot.value.user
}
async function loadStats() {
  const v = ++statsVersion
  statsController?.abort()
  statsController = new AbortController()
  statsError.value = ''
  statsBusy.value = true
  try {
    const d = await api(`/admin/statistics?start=${range.start}&end=${range.end}`, {
      signal: statsController.signal
    })
    if (v === statsVersion) stats.value = d
  } catch (e) {
    if (e.name !== 'AbortError') statsError.value = errorText(e)
  } finally {
    if (v === statsVersion) statsBusy.value = false
  }
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    await csrf()
    session.user = await api('/auth/me')
    await refresh()
    range.start = today()
    range.end = today()
    if (admin.value) await loadStats()
    if (route.path === '/login') router.replace('/')
  } catch (e) {
    if (e.status !== 401) error.value = errorText(e)
  } finally {
    loading.value = false
  }
}
async function login() {
  loginError.value = ''
  if (!loginForm.account || !loginForm.password) {
    loginError.value = '请填写账号和密码'
    await nextTick()
    summary.value?.focus()
    return
  }
  loginBusy.value = true
  try {
    await csrf()
    const d = await api('/auth/login', { method: 'POST', body: loginForm })
    session.user = d.user
    session.expired = false
    loginForm.password = ''
    await refresh()
    range.start = today()
    range.end = today()
    if (admin.value) await loadStats()
    router.replace('/')
  } catch (e) {
    loginError.value = errorText(e)
    await nextTick()
    summary.value?.focus()
  } finally {
    loginBusy.value = false
  }
}
async function signout() {
  try {
    await logout()
    boot.value = null
    detailId.value = null
    editor.value = null
    router.replace('/login')
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
async function saved() {
  editor.value = null
  ElMessage.success('操作已保存')
  if (!session.user) return
  await updated()
  if (admin.value && view.value === 'home') await loadStats()
}
async function updated() {
  try {
    await refresh()
    await viewRef.value?.load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}
function changePassword() {
  editor.value = {
    title: '修改密码',
    path: '/me/password',
    values: { oldPassword: '', newPassword: '' },
    fields: [
      { name: 'oldPassword', label: '原密码', type: 'password', required: true },
      { name: 'newPassword', label: '新密码', type: 'password', required: true }
    ],
    help: '修改后退出所有旧登录，请使用新密码重新登录。'
  }
}
watch(view, async () => {
  detailId.value = null
  editor.value = null
  if (admin.value && view.value === 'home' && boot.value) await loadStats()
})
watch(
  () => session.user,
  (user) => {
    if (!user) {
      detailId.value = null
      editor.value = null
      boot.value = null
    }
  }
)
onMounted(initialize)
onUnmounted(() => statsController?.abort())
</script>
<template>
  <div v-if="loading" class="startup">正在连接图书馆服务…</div>
  <div v-else-if="!session.user || !boot" class="login-body">
    <main class="login-shell">
      <section class="login-intro">
        <img
          class="login-photo"
          :src="'/images/library-windows.webp'"
          alt="图书馆阅读空间示意图"
          width="955"
          height="1440"
        />
        <div class="login-photo-shade" />
        <div class="wordmark">
          <span class="brand-mark">图</span>
          <div>图书馆<span class="brand-sub">座位预约管理系统</span></div>
        </div>
        <h1>座位预约服务</h1>
        <p class="intro-copy">查询阅览室开放安排，预约自习座位，<br />管理签到与使用记录。</p>
        <div class="service-guide">
          <h2>使用说明</h2>
          <ol>
            <li>选择日期、时段与阅览室。</li>
            <li>确认预约，到馆后完成签到。</li>
            <li>暂离登记，离馆前完成签退。</li>
          </ol>
        </div>
        <p class="login-foot">请按时使用座位。环境照片为示意图。</p>
      </section>
      <section class="login-form-panel">
        <h2>账号登录</h2>
        <p class="muted">学生与管理员使用同一入口登录。</p>
        <div v-if="session.expired" role="status" class="alert info">
          登录已到期或凭据已更新，请重新登录。
        </div>
        <p v-if="error" role="alert" class="form-error">
          {{ error }}<el-button @click="initialize">重新连接</el-button>
        </p>
        <form class="login-form" @submit.prevent="login">
          <div v-if="loginError" ref="summary" tabindex="-1" class="form-error" role="alert">
            {{ loginError }}
          </div>
          <label for="account"
            >账号<input
              id="account"
              v-model="loginForm.account"
              name="account"
              maxlength="32"
              autocomplete="username"
              placeholder="请输入学号或管理员账号" /></label
          ><label for="password"
            >密码<input
              id="password"
              v-model="loginForm.password"
              name="password"
              type="password"
              maxlength="64"
              autocomplete="current-password"
              placeholder="请输入密码" /></label
          ><el-button type="primary" native-type="submit" :loading="loginBusy">登录</el-button>
        </form>
        <p class="muted small">账号由管理员提供，忘记密码请联系管理员。</p>
      </section>
    </main>
  </div>
  <template v-else
    ><aside class="sidebar">
      <router-link class="wordmark" to="/"
        ><span class="brand-mark">图</span>
        <div>图书馆<span class="brand-sub">座位预约管理系统</span></div></router-link
      >
      <p class="nav-caption">{{ admin ? '管理工作台' : '学习空间' }}</p>
      <nav aria-label="主导航">
        <router-link
          v-for="[key, label] in navigation"
          :key="key"
          :to="key === 'home' ? '/' : '/' + key"
          :class="{ active: view === key }"
          >{{ label }}</router-link
        >
      </nav>
      <div class="sidebar-bottom">
        <p>座位预约服务</p>
        <span>PC端 · 图书馆服务</span>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumb">
          图书馆 <span>/</span><b>{{ title }}</b>
        </div>
        <div class="user-menu">
          <span class="role">{{ admin ? '管理员' : '学生' }}</span
          ><span>{{ session.user.name }}</span
          ><el-button text @click="signout">退出登录</el-button>
        </div>
      </header>
      <main class="content">
        <BookView
          v-if="view === 'book' && !admin"
          :boot="boot"
          @detail="detailId = $event"
          @updated="updated"
        />
        <RecordsView
          v-else-if="['reservations', 'violations'].includes(view)"
          ref="viewRef"
          :kind="view"
          :admin="admin"
          @detail="detailId = $event"
          @editor="editor = $event"
        />
        <AdminView
          v-else-if="
            admin && ['rooms', 'seats', 'users', 'notices', 'rules', 'audits'].includes(view)
          "
          ref="viewRef"
          :view="view"
          :boot="boot"
          @editor="editor = $event"
        />
        <template v-else-if="view === 'profile'"
          ><div class="heading">
            <div>
              <h1>个人信息</h1>
              <p>维护自己的账号和登录密码。</p>
            </div>
          </div>
          <section class="panel">
            <h2>账号信息</h2>
            <dl class="detail-list profile-list">
              <div>
                <dt>学号 / 账号</dt>
                <dd>{{ session.user.studentNo }}</dd>
              </div>
              <div>
                <dt>姓名</dt>
                <dd>{{ session.user.name }}</dd>
              </div>
              <div>
                <dt>班级</dt>
                <dd>{{ session.user.className }}</dd>
              </div>
              <div>
                <dt>角色</dt>
                <dd>{{ admin ? '管理员' : '学生' }}</dd>
              </div>
            </dl>
            <el-button type="primary" @click="changePassword">修改密码</el-button>
          </section></template
        >
        <template v-else-if="view === 'notices'"
          ><div class="heading">
            <div>
              <h1>公告与预约规则</h1>
              <p>查看最新安排和规则版本。</p>
            </div>
          </div>
          <div class="two-column">
            <section class="panel">
              <h2>最新公告</h2>
              <article v-for="n in boot.notices" :key="n.id" class="notice">
                <h3>{{ n.title }}</h3>
                <p>{{ n.content }}</p>
                <time>{{ fmt(n.createdAt) }}</time>
              </article>
            </section>
            <section class="panel">
              <h2>演示规则 · 版本 {{ boot.rule.id }}</h2>
              <ul class="rule-list">
                <li>可预约今日至未来 {{ boot.rule.advanceDays }} 天。</li>
                <li>30分钟网格，单次最多 {{ boot.rule.maxDurationMinutes }} 分钟。</li>
                <li>每人同时最多1条有效预约。</li>
                <li>
                  提前 {{ boot.rule.checkinEarlyMinutes }} 分钟可签到，开始后
                  {{ boot.rule.checkinGraceMinutes }} 分钟截止。
                </li>
                <li>
                  暂离 {{ boot.rule.leaveLimit }} 次，每次 {{ boot.rule.leaveMinutes }} 分钟。
                </li>
                <li>
                  {{ boot.rule.violationWindowDays }} 天内 {{ boot.rule.violationLimit }} 次违约限制
                  {{ boot.rule.blockDays }} 天。
                </li>
              </ul>
            </section>
          </div></template
        >
        <template v-else-if="view === 'home' && !admin"
          ><div class="heading">
            <div>
              <h1>我的工作台</h1>
              <p>{{ session.user.name }}，欢迎使用图书馆座位预约服务。</p>
            </div>
            <el-button @click="refresh">刷新信息</el-button>
          </div>
          <section class="study-banner">
            <div class="study-banner-copy">
              <h2>预约自习座位</h2>
              <p>选择日期与时段，查看各阅览室可用座位。</p>
              <router-link class="primary action-link" to="/book">去预约</router-link
              ><span class="banner-note">到馆后签到，离馆前签退</span>
            </div>
            <figure>
              <img
                :src="'/images/library-windows.webp'"
                alt="自然采光的图书馆空间示意图"
                width="955"
                height="1440"
              />
              <figcaption>环境示意图</figcaption>
            </figure>
          </section>
          <div class="metrics">
            <div class="metric">
              <p>开放阅览室</p>
              <strong>{{ boot.rooms.filter((r) => r.enabled).length }}</strong>
            </div>
            <div class="metric">
              <p>启用座位</p>
              <strong>{{
                boot.rooms.filter((r) => r.enabled).reduce((n, r) => n + r.seatCount, 0)
              }}</strong
              ><small>教学种子资料，不代表学校规模</small>
            </div>
            <div class="metric">
              <p>可预约日期</p>
              <strong>{{ boot.rule.advanceDays + 1 }} 天</strong>
            </div>
            <div class="metric">
              <p>最长预约</p>
              <strong>{{ boot.rule.maxDurationMinutes / 60 }} 小时</strong>
            </div>
          </div>
          <div class="two-column">
            <section class="panel">
              <div class="panel-title">
                <h2>当前预约</h2>
                <router-link to="/reservations">查看全部</router-link>
              </div>
              <div v-if="boot.currentReservation" class="record-card">
                <div class="record-top">
                  <strong>{{ boot.currentReservation.seatNo }}</strong
                  ><RecordStatus :record="boot.currentReservation" />
                </div>
                <p>{{ boot.currentReservation.roomName }}</p>
                <p>
                  {{ fmt(boot.currentReservation.startTime) }} —
                  {{ boot.currentReservation.endTime.slice(11, 16) }}
                </p>
                <el-button type="primary" @click="detailId = boot.currentReservation.id"
                  >查看预约</el-button
                >
              </div>
              <div v-else class="empty">
                <strong>暂无进行中的预约</strong
                ><router-link to="/book">选择时段并预约座位</router-link>
              </div>
            </section>
            <section class="panel">
              <h2>图书馆公告</h2>
              <article v-for="n in boot.notices.slice(0, 2)" :key="n.id" class="notice">
                <h3>{{ n.title }}</h3>
                <p>{{ n.content }}</p>
                <time>{{ fmt(n.createdAt) }}</time>
              </article>
            </section>
          </div></template
        >
        <template v-else-if="view === 'home' && admin"
          ><div class="heading">
            <div>
              <h1>管理概览</h1>
              <p>查看当前使用与指定日期范围的预约数据。</p>
            </div>
            <el-button @click="loadStats">刷新</el-button>
          </div>
          <p v-if="statsError" role="alert" class="form-error">{{ statsError }}</p>
          <div v-if="stats" class="metrics">
            <div
              v-for="[key, label] in [
                ['inUse', '当前使用中'],
                ['away', '当前暂离'],
                ['waiting', '待签到预约'],
                ['seats', '启用座位']
              ]"
              :key="key"
              class="metric"
            >
              <p>{{ label }}</p>
              <strong>{{ stats[key] }}</strong>
            </div>
          </div>
          <section class="panel" v-loading="statsBusy">
            <h2>预约统计</h2>
            <form class="toolbar" @submit.prevent="loadStats">
              <label>开始日期<input type="date" v-model="range.start" required /></label
              ><label>结束日期<input type="date" v-model="range.end" required /></label
              ><el-button type="primary" native-type="submit">查询</el-button>
            </form>
            <div v-if="stats" class="metrics">
              <div class="metric">
                <p>预约总数</p>
                <strong>{{ stats.reservations }}</strong>
              </div>
              <div class="metric">
                <p>签到率</p>
                <strong>{{
                  Number(stats.due) ? Math.round((stats.arrived / stats.due) * 100) + '%' : '—'
                }}</strong
                ><small>{{ stats.arrived }} / {{ stats.due }} 条应到预约</small>
              </div>
              <div class="metric">
                <p>未签到超时</p>
                <strong>{{ stats.noShow }}</strong>
              </div>
              <div class="metric">
                <p>暂离超时</p>
                <strong>{{ stats.leaveTimeout }}</strong>
              </div>
            </div>
            <p class="stat-note">
              按预约开始日期统计。签到率排除取消和管理关闭；按钮签到不证明实际到馆。到期未扫描记录按有效状态只读计算。
            </p>
          </section></template
        >
        <section v-else class="panel empty">
          <h1>页面不存在或无访问权限</h1>
          <router-link to="/">返回工作台</router-link>
        </section>
      </main>
      <footer class="page-footer">图书馆座位预约管理系统<span>按时签到，文明使用座位</span></footer>
    </div>
    <EditorDialog v-if="editor" :editor="editor" @close="editor = null" @saved="saved" />
    <ReservationDetail
      v-if="detailId"
      :id="detailId"
      :admin="admin"
      @close="detailId = null"
      @updated="updated"
    />
  </template>
</template>
