<script setup>
import { ref, computed, watch, onUnmounted } from 'vue'
import { api } from '../api'
import { fmt, today, localTime, serverNow, errorText } from '../time'
const props = defineProps({ view: String, boot: Object })
const emit = defineEmits(['editor'])
const rows = ref([]),
  openings = ref([]),
  roomId = ref(props.boot.rooms[0]?.id),
  search = ref(''),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref('')
let controller,
  version = 0
const titles = {
  rooms: '阅览室管理',
  seats: '座位管理',
  users: '学生管理',
  notices: '公告管理',
  rules: '预约规则',
  audits: '操作日志'
}
const f = (name, label, type = 'text', extra = {}) => ({
  name,
  label,
  type,
  required: !['checkbox'].includes(type),
  ...extra
})
const reason = f('reason', '操作原因', 'textarea', { max: 200 })
const ruleFields = [
  ['advanceDays', '可预约未来天数', 0, 14],
  ['maxDurationMinutes', '单次最长分钟数', 30, 480],
  ['checkinEarlyMinutes', '提前签到分钟数', 0, 30],
  ['checkinGraceMinutes', '签到宽限分钟数', 1, 29],
  ['leaveMinutes', '暂离最长分钟数', 1, 120],
  ['leaveLimit', '暂离次数', 0, 10],
  ['violationWindowDays', '违约统计天数', 1, 30],
  ['violationLimit', '限制触发次数', 1, 20],
  ['blockDays', '限制预约天数', 1, 30]
]
const columns = computed(
  () =>
    ({
      rooms: [
        ['code', '编号'],
        ['name', '名称'],
        ['floor', '楼层'],
        ['seatCount', '启用座位'],
        ['openTime', '开放时间'],
        ['closeTime', '关闭时间'],
        ['enabled', '启用']
      ],
      seats: [
        ['seatNo', '座位编号'],
        ['rowNo', '行'],
        ['colNo', '列'],
        ['hasSocket', '插座'],
        ['nearWindow', '靠窗'],
        ['enabled', '启用']
      ],
      users: [
        ['studentNo', '账号'],
        ['name', '姓名'],
        ['className', '班级'],
        ['role', '角色'],
        ['enabled', '启用'],
        ['blockedUntil', '限制预约至']
      ],
      notices: [
        ['title', '标题'],
        ['content', '内容'],
        ['published', '已发布'],
        ['createdAt', '创建时间']
      ],
      rules: [
        ['id', '版本'],
        ['effectiveAt', '生效时间'],
        ['maxDurationMinutes', '最大分钟数'],
        ['checkinGraceMinutes', '签到宽限'],
        ['leaveMinutes', '暂离分钟数']
      ],
      audits: [
        ['createdAt', '时间'],
        ['operatorName', '操作人'],
        ['operation', '操作'],
        ['targetType', '对象'],
        ['targetId', '对象ID'],
        ['reason', '原因']
      ]
    })[props.view]
)
async function load() {
  const v = ++version
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    if (props.view === 'rooms') {
      const result = await Promise.all([
        api('/admin/rooms', { signal: controller.signal }),
        api('/admin/openings', { signal: controller.signal })
      ])
      if (v === version) {
        rows.value = result[0]
        openings.value = result[1]
      }
    } else {
      const path =
        props.view === 'seats'
          ? `/admin/rooms/${roomId.value}/seats`
          : props.view === 'users'
            ? '/admin/users?search=' + encodeURIComponent(search.value)
            : props.view === 'audits'
              ? '/admin/audits?page=' + page.value
              : '/admin/' + props.view
      const data = await api(path, { signal: controller.signal })
      if (v === version) {
        rows.value = data.items || data
        total.value = data.total || 0
      }
    }
  } catch (e) {
    if (e.name !== 'AbortError') error.value = errorText(e)
  } finally {
    if (v === version) loading.value = false
  }
}
watch(
  () => props.view,
  () => {
    page.value = 1
    search.value = ''
    load()
  },
  { immediate: true }
)
onUnmounted(() => controller?.abort())
defineExpose({ load })
function edit(row = {}) {
  let fields,
    values = { ...row },
    path = '/admin/' + props.view + (row.id ? '/' + row.id : ''),
    method = row.id ? 'PUT' : 'POST',
    help = ''
  if (props.view === 'rooms') {
    fields = [
      f('code', '编号', 'text', { max: 32 }),
      f('name', '名称', 'text', { max: 60 }),
      f('floor', '楼层', 'text', { max: 40 }),
      f('description', '说明', 'textarea', { required: false, max: 500 }),
      f('openTime', '开放时间', 'time', { step: 1800 }),
      f('closeTime', '关闭时间', 'time', { step: 1800 }),
      f('enabled', '启用', 'checkbox'),
      reason
    ]
    values = { enabled: true, openTime: '08:00', closeTime: '22:00', description: '', ...row }
    help = '停用或缩短开放时间会关闭受影响预约，保留原因。'
  }
  if (props.view === 'seats') {
    fields = [
      f('seatNo', '座位编号', 'text', { max: 20 }),
      f('rowNo', '行号', 'number', { min: 1, max: 30 }),
      f('colNo', '列号', 'number', { min: 1, max: 20 }),
      f('hasSocket', '带插座', 'checkbox'),
      f('nearWindow', '靠窗', 'checkbox'),
      f('enabled', '启用', 'checkbox'),
      reason
    ]
    values = {
      roomId: roomId.value,
      rowNo: 1,
      colNo: 1,
      hasSocket: false,
      nearWindow: false,
      enabled: true,
      ...row
    }
    help = '编号和位置在房间内唯一；停用会关闭有效预约。'
  }
  if (props.view === 'users') {
    fields = [
      f('studentNo', '学号', 'text', { max: 32 }),
      f('name', '姓名', 'text', { max: 50 }),
      f('className', '班级', 'text', { required: false, max: 100 }),
      f('password', '初始密码', 'password')
    ]
    values = { className: '' }
    path = '/admin/users'
    method = 'POST'
    help = '创建学生账号，密码至少8位。'
  }
  if (props.view === 'notices') {
    fields = [
      f('title', '标题', 'text', { max: 100 }),
      f('content', '内容', 'textarea', { max: 5000 }),
      f('published', '发布到学生端', 'checkbox')
    ]
    values = { published: true, ...row }
  }
  if (props.view === 'rules') {
    fields = [
      f('effectiveAt', '生效时间', 'datetime-local'),
      ...ruleFields.map(([name, label, min, max]) =>
        f(name, label, 'number', { min, max, step: name === 'maxDurationMinutes' ? 30 : 1 })
      )
    ]
    values = { ...props.boot.rule, effectiveAt: localTime(serverNow() + 120000).slice(0, 16) }
    delete values.id
    path = '/admin/rules'
    method = 'POST'
    help = '新版本只影响生效后创建的预约，旧记录沿用原rule_id。'
  }
  emit('editor', {
    title: row.id ? '编辑' + titles[props.view] : '新增' + titles[props.view],
    fields,
    values,
    path,
    method,
    help,
    confirm: ['rooms', 'seats'].includes(props.view)
      ? '确定保存场地变更？不再满足开放条件的有效预约会关闭。'
      : null
  })
}
function opening(o = {}) {
  emit('editor', {
    title: o.id ? '编辑日期开放安排' : '设置日期开放安排',
    path: '/admin/openings',
    values: {
      roomId: props.boot.rooms[0]?.id,
      specialDate: today(),
      openTime: '08:00',
      closeTime: '22:00',
      isClosed: false,
      reason: '',
      ...o
    },
    fields: [
      f('roomId', '阅览室', 'select', {
        disabled: !!o.id,
        options: props.boot.rooms.map((r) => ({ value: r.id, label: r.name }))
      }),
      f('specialDate', '日期', 'date', { disabled: !!o.id, min: today() }),
      f('openTime', '开放时间', 'time', { step: 1800 }),
      f('closeTime', '关闭时间', 'time', { step: 1800 }),
      f('isClosed', '全天闭馆', 'checkbox'),
      reason
    ],
    confirm: '确定更新开放安排？受影响预约将关闭，恢复开放不会恢复已关闭预约。',
    help: '日期例外优先于日常时间；过去的安排只读。'
  })
}
function userAction(row, reset) {
  emit('editor', {
    title: reset ? '重置学生密码' : row.enabled ? '停用学生' : '启用学生',
    path: `/admin/users/${row.id}/${reset ? 'password' : 'enabled'}`,
    values: reset ? { reason: '', newPassword: '' } : { reason: '', enabled: !row.enabled },
    fields: reset ? [f('newPassword', '新密码', 'password'), reason] : [reason],
    confirm: reset
      ? '确定重置密码并使旧登录失效？'
      : row.enabled
        ? '确定停用并关闭此学生的有效预约？'
        : '确定启用该账号？',
    help: reset ? '保留预约与违约，旧会话在下次请求失效。' : '保留原记录与管理原因。'
  })
}
function cell(value) {
  return typeof value === 'boolean'
    ? value
      ? '是'
      : '否'
    : value == null
      ? '—'
      : String(value).includes('T')
        ? fmt(value)
        : value
}
</script>
<template>
  <div class="heading">
    <div>
      <h1>{{ titles[view] }}</h1>
      <p>维护信息与处理原因，历史数据保留可追溯记录。</p>
    </div>
    <el-button v-if="view !== 'audits'" type="primary" @click="edit()">新增</el-button>
  </div>
  <section v-if="view === 'rules'" class="panel">
    <h2>当前生效版本 {{ boot.rule.id }}</h2>
    <div class="rule-grid">
      <dl v-for="[name, label] in ruleFields" :key="name">
        <dt>{{ label }}</dt>
        <dd>{{ boot.rule[name] }}</dd>
      </dl>
    </div>
  </section>
  <section class="panel">
    <form v-if="view === 'users'" class="toolbar" @submit.prevent="load">
      <label
        >学号 / 姓名<el-input
          v-model="search"
          placeholder="搜索学生"
          maxlength="100"
          clearable /></label
      ><el-button native-type="submit" type="primary">查询</el-button
      ><span class="muted">最多显示200条，请使用搜索定位。</span>
    </form>
    <div v-if="view === 'seats'" class="toolbar">
      <label
        >阅览室<el-select v-model="roomId" @change="load"
          ><el-option
            v-for="r in boot.rooms"
            :value="r.id"
            :key="r.id"
            :label="r.name" /></el-select
      ></label>
    </div>
    <p v-if="error" role="alert" class="form-error">
      {{ error }}<el-button @click="load">重试</el-button>
    </p>
    <el-table :data="rows" v-loading="loading" empty-text="暂无记录"
      ><el-table-column
        v-for="[name, label] in columns"
        :key="name"
        :prop="name"
        :label="label"
        :min-width="['reason', 'content', 'name'].includes(name) ? 160 : 100"
        :show-overflow-tooltip="true"
        ><template #default="{ row }">{{ cell(row[name]) }}</template></el-table-column
      ><el-table-column
        v-if="!['rules', 'audits'].includes(view)"
        label="操作"
        :min-width="view === 'users' ? 180 : 100"
        fixed="right"
        ><template #default="{ row }"
          ><template v-if="view === 'users'"
            ><el-button v-if="row.role === 'STUDENT'" size="small" @click="userAction(row, true)"
              >重置密码</el-button
            ><el-button
              v-if="row.role === 'STUDENT'"
              size="small"
              @click="userAction(row, false)"
              >{{ row.enabled ? '停用' : '启用' }}</el-button
            ></template
          ><el-button v-else size="small" @click="edit(row)">编辑</el-button></template
        ></el-table-column
      ></el-table
    ><el-pagination
      v-if="view === 'audits'"
      v-model:current-page="page"
      :page-size="12"
      :total="total"
      layout="total,prev,pager,next"
      @current-change="load"
    />
  </section>
  <section v-if="view === 'rooms'" class="panel">
    <div class="panel-title">
      <h2>特殊日期开放安排</h2>
      <el-button @click="opening()">设置日期安排</el-button>
    </div>
    <el-table :data="openings" empty-text="默认按日常时间开放"
      ><el-table-column label="阅览室"
        ><template #default="{ row }">{{
          boot.rooms.find((r) => r.id === row.roomId)?.name
        }}</template></el-table-column
      ><el-table-column prop="specialDate" label="日期" /><el-table-column label="开放情况"
        ><template #default="{ row }">{{
          row.isClosed ? '全天闭馆' : row.openTime.slice(0, 5) + '—' + row.closeTime.slice(0, 5)
        }}</template></el-table-column
      ><el-table-column prop="reason" label="原因" /><el-table-column label="操作"
        ><template #default="{ row }"
          ><el-button v-if="row.specialDate >= today()" size="small" @click="opening(row)"
            >编辑</el-button
          ><span v-else>历史安排</span></template
        ></el-table-column
      ></el-table
    >
  </section>
</template>
