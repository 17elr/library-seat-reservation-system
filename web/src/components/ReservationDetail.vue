<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { api } from '../api'
import { syncTime, serverNow, epochOf, fmt, errorText } from '../time'
import RecordStatus from './RecordStatus.vue'
const props = defineProps({ id: Number, admin: Boolean })
const emit = defineEmits(['close', 'updated'])
const data = ref(null),
  busy = ref(false),
  error = ref(''),
  tick = ref(0)
let timer, controller, lastDeadline
const active = ['RESERVED', 'CHECKED_IN', 'TEMP_LEFT']
async function load() {
  controller?.abort()
  controller = new AbortController()
  try {
    data.value = await api('/reservations/' + props.id, { signal: controller.signal })
    syncTime(data.value.serverTime)
    error.value = ''
  } catch (e) {
    if (e.name !== 'AbortError') error.value = e.message
  }
}
const r = computed(() => data.value?.reservation),
  q = computed(() => data.value?.rule)
const now = computed(() => {
  tick.value
  return serverNow()
})
const target = computed(() =>
  !r.value
    ? null
    : r.value.status === 'TEMP_LEFT'
      ? epochOf(r.value.leaveDeadline)
      : r.value.status === 'RESERVED'
        ? now.value < epochOf(r.value.startTime) - q.value.checkinEarlyMinutes * 60000
          ? epochOf(r.value.startTime) - q.value.checkinEarlyMinutes * 60000
          : epochOf(r.value.startTime) + q.value.checkinGraceMinutes * 60000
        : epochOf(r.value.endTime)
)
const countdown = computed(() => {
  let n = Math.max(0, Math.ceil((target.value - now.value) / 1000))
  return [Math.floor(n / 3600), Math.floor(n / 60) % 60, n % 60]
    .map((v) => String(v).padStart(2, '0'))
    .join(':')
})
const actions = computed(() => {
  if (!r.value || props.admin || r.value.stateComputed) return []
  const s = r.value.status,
    n = now.value,
    begin = epochOf(r.value.startTime),
    end = epochOf(r.value.endTime)
  if (n >= end) return []
  if (s === 'RESERVED')
    return [
      ...(n < begin ? [['cancel', '取消预约']] : []),
      ...(n >= begin - q.value.checkinEarlyMinutes * 60000 &&
      n <= begin + q.value.checkinGraceMinutes * 60000
        ? [['check-in', '到场签到']]
        : [])
    ]
  if (s === 'CHECKED_IN')
    return [
      ...(n >= begin && r.value.leaveCount < q.value.leaveLimit ? [['leave', '暂离']] : []),
      ['check-out', '结束使用 / 签退']
    ]
  if (s === 'TEMP_LEFT')
    return [
      ['return', '已返回座位'],
      ['check-out', '结束使用 / 签退']
    ]
  return []
})
async function action(name) {
  if (
    ['cancel', 'check-out'].includes(name) &&
    !window.confirm('确定' + (name === 'cancel' ? '取消预约' : '结束使用并释放座位') + '吗？')
  )
    return
  busy.value = true
  try {
    await api(`/reservations/${props.id}/${name}`, { method: 'POST' })
    await load()
    emit('updated')
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}
function visibility() {
  clearInterval(timer)
  controller?.abort()
  if (!document.hidden) {
    load()
    timer = setInterval(() => {
      tick.value++
      const deadline = target.value
      if (
        r.value &&
        active.includes(r.value.status) &&
        !r.value.stateComputed &&
        deadline &&
        serverNow() > deadline &&
        lastDeadline !== deadline
      ) {
        lastDeadline = deadline
        load()
      }
    }, 1000)
  }
}
onMounted(() => {
  visibility()
  document.addEventListener('visibilitychange', visibility)
})
onUnmounted(() => {
  clearInterval(timer)
  controller?.abort()
  document.removeEventListener('visibilitychange', visibility)
})
</script>
<template>
  <el-dialog :model-value="true" title="预约详情" width="720px" @close="emit('close')"
    ><p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <div v-if="data">
      <div class="detail-heading">
        <strong>{{ r.seatNo }}</strong
        ><RecordStatus :record="r" />
      </div>
      <dl class="detail-list">
        <div>
          <dt>预约编号</dt>
          <dd>{{ r.reservationNo }}</dd>
        </div>
        <div>
          <dt>阅览室</dt>
          <dd>{{ r.roomName }}</dd>
        </div>
        <div v-if="admin">
          <dt>学生</dt>
          <dd>{{ r.userName }} · {{ r.studentNo }}</dd>
        </div>
        <div>
          <dt>预约时段</dt>
          <dd>{{ fmt(r.startTime) }} — {{ r.endTime.slice(11, 16) }}</dd>
        </div>
        <div>
          <dt>暂离次数</dt>
          <dd>{{ r.leaveCount }} / {{ q.leaveLimit }}</dd>
        </div>
        <div>
          <dt>签到截止</dt>
          <dd>{{ fmt(r.checkinDeadline) }}</dd>
        </div>
      </dl>
      <div v-if="active.includes(r.status)" class="deadline-panel">
        <span>{{
          r.status === 'TEMP_LEFT'
            ? '返回倒计时'
            : r.status === 'RESERVED'
              ? '签到窗口提示'
              : '使用倒计时'
        }}</span
        ><strong>{{ countdown }}</strong
        ><el-button size="small" @click="load">刷新状态</el-button>
      </div>
      <p v-if="r.stateComputed" class="alert info">
        时段已结束，显示状态按规则计算。事件和违约等待后台扫描归档。
      </p>
      <p v-if="r.finishReason" class="alert info">{{ r.finishReason }}</p>
      <h3>操作记录</h3>
      <ol class="event-list">
        <li v-for="e in data.events" :key="e.id">
          <b>{{ e.reason }}</b
          ><small>{{ fmt(e.occurredAt) }} · {{ e.actorId ? '用户操作' : '系统处理' }}</small>
        </li>
      </ol>
      <div class="form-actions">
        <el-button
          v-for="a in actions"
          :key="a[0]"
          :loading="busy"
          :disabled="busy"
          :type="a[0] === 'cancel' ? 'default' : 'primary'"
          @click="action(a[0])"
          >{{ a[1] }}</el-button
        >
      </div>
    </div>
    <el-skeleton v-else :rows="6" animated
  /></el-dialog>
</template>
