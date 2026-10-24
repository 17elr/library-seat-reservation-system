<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { api, session } from '../api'
import { today, dateAdd, syncTime, minutes, hhmm, roomPhoto, errorText } from '../time'
const props = defineProps({ boot: Object })
const emit = defineEmits(['detail', 'updated'])
const date = ref(today()),
  roomId = ref(props.boot.rooms.find((r) => r.enabled)?.id),
  start = ref(''),
  end = ref(''),
  schedules = ref([]),
  seats = ref([]),
  selected = ref(null),
  loading = ref(false),
  busy = ref(false),
  message = ref(''),
  queryError = ref(''),
  confirm = ref(false),
  socket = ref(false),
  windowSeat = ref(false),
  search = ref(''),
  requestKey = ref(null)
let controller,
  generation = 0
const options = computed(() =>
  Array.from({ length: props.boot.rule.advanceDays + 1 }, (_, i) => dateAdd(today(), i))
)
const rooms = computed(() => props.boot.rooms.filter((r) => r.enabled)),
  room = computed(() => rooms.value.find((r) => r.id === roomId.value)),
  schedule = computed(() => schedules.value.find((r) => r.roomId === roomId.value))
const starts = computed(() =>
  !schedule.value?.bookable
    ? []
    : Array.from(
        {
          length: Math.max(
            0,
            (minutes(schedule.value.closeTime) - minutes(schedule.value.earliestStart)) / 30
          )
        },
        (_, i) => hhmm(minutes(schedule.value.earliestStart) + i * 30)
      )
)
const ends = computed(() =>
  !start.value || !schedule.value
    ? []
    : Array.from(
        {
          length:
            Math.min(
              props.boot.rule.maxDurationMinutes,
              minutes(schedule.value.closeTime) - minutes(start.value)
            ) / 30
        },
        (_, i) => hhmm(minutes(start.value) + (i + 1) * 30)
      )
)
const pendingKey = () => 'library:pending:' + session.user.id
const restriction = computed(() =>
  props.boot.currentReservation
    ? '您已有一条有效预约，请先结束或取消。'
    : props.boot.user.blockedUntil &&
        props.boot.user.blockedUntil.replace('T', ' ') > props.boot.now.replace('T', ' ')
      ? '当前限制预约至 ' + props.boot.user.blockedUntil.replace('T', ' ')
      : ''
)
const matching = computed(() =>
  seats.value.filter(
    (s) =>
      s.seatNo.toLowerCase().includes(search.value.toLowerCase()) &&
      (!socket.value || s.hasSocket) &&
      (!windowSeat.value || s.nearWindow)
  )
)
const available = computed(() => matching.value.filter((s) => s.available))
const cols = computed(() => Math.max(8, ...seats.value.map((s) => s.colNo)))
function changeSelection() {
  selected.value = null
  requestKey.value = null
}
async function refreshSchedules() {
  const g = ++generation
  controller?.abort()
  controller = new AbortController()
  queryError.value = ''
  loading.value = true
  changeSelection()
  try {
    const d = await api('/rooms/schedules?date=' + date.value, { signal: controller.signal })
    if (g !== generation) return
    syncTime(d.serverTime)
    schedules.value = d.rooms
    if (!starts.value.includes(start.value)) start.value = starts.value[0] || ''
    if (!ends.value.includes(end.value))
      end.value = ends.value[Math.min(3, ends.value.length - 1)] || ''
    await loadSeats(g)
  } catch (e) {
    if (e.name !== 'AbortError') queryError.value = errorText(e)
  } finally {
    if (g === generation) loading.value = false
  }
}
async function loadSeats(g = ++generation) {
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  queryError.value = ''
  changeSelection()
  seats.value = []
  if (!start.value || !end.value) {
    loading.value = false
    return
  }
  try {
    const d = await api(
      `/rooms/${roomId.value}/seats?start=${date.value}T${start.value}:00&end=${date.value}T${end.value}:00`,
      { signal: controller.signal }
    )
    if (g === generation) seats.value = d.seats
  } catch (e) {
    if (e.name !== 'AbortError') queryError.value = errorText(e)
  } finally {
    if (g === generation) loading.value = false
  }
}
function choose(s) {
  if (!s.available) return
  pending.value = null
  sessionStorage.removeItem(pendingKey())
  selected.value = s
  requestKey.value = crypto.randomUUID()
  message.value = ''
}
function auto() {
  if (available.value.length) choose(available.value[0])
}
function duration(n) {
  end.value = hhmm(minutes(start.value) + n)
  loadSeats()
}
function startChanged() {
  if (!ends.value.includes(end.value))
    end.value = ends.value[Math.min(3, ends.value.length - 1)] || ''
  loadSeats()
}
watch([date, roomId], refreshSchedules)
watch([socket, windowSeat, search], () => {
  if (selected.value && !matching.value.some((s) => s.id === selected.value.id)) changeSelection()
})
async function submit(payload) {
  busy.value = true
  message.value = ''
  try {
    sessionStorage.setItem(pendingKey(), JSON.stringify(payload))
    const r = await api('/reservations', { method: 'POST', body: payload })
    sessionStorage.removeItem(pendingKey())
    confirm.value = false
    emit('updated')
    emit('detail', r.id)
  } catch (e) {
    message.value = errorText(e)
    if (e.status === 409) {
      confirm.value = false
      await loadSeats()
      message.value = e.message + '；请重新选座。'
    }
  } finally {
    busy.value = false
  }
}
function book() {
  if (!selected.value) return
  const payload = {
    seatId: selected.value.id,
    startTime: date.value + 'T' + start.value + ':00',
    endTime: date.value + 'T' + end.value + ':00',
    requestKey: requestKey.value
  }
  submit(payload)
}
const pending = ref(null)
onMounted(async () => {
  const stored = sessionStorage.getItem(pendingKey())
  if (stored)
    try {
      pending.value = JSON.parse(stored)
    } catch {}
  await refreshSchedules()
})
function retryPending() {
  const p = pending.value
  pending.value = null
  submit(p)
}
function visibility() {
  if (document.hidden) {
    controller?.abort()
    generation++
    loading.value = false
  } else refreshSchedules()
}
onMounted(() => document.addEventListener('visibilitychange', visibility))
onUnmounted(() => {
  controller?.abort()
  generation++
  document.removeEventListener('visibilitychange', visibility)
})
</script>
<template>
  <div class="heading">
    <div>
      <h1>预约座位</h1>
      <p>选择时段、挑选座位，确认后完成预约。</p>
    </div>
  </div>
  <div v-if="pending" class="booking-warning">
    <span>有一次提交尚未确认结果。</span
    ><el-button @click="retryPending">沿用原标识确认结果</el-button>
  </div>
  <div v-if="restriction" class="booking-warning">
    <span>{{ restriction }}</span
    ><el-button v-if="boot.currentReservation" @click="emit('detail', boot.currentReservation.id)"
      >查看当前预约</el-button
    >
  </div>
  <p v-if="message" class="form-error" role="alert">{{ message }}</p>
  <section class="panel">
    <div class="booking-step">
      <b>1</b>选择日期和时段<small>以实际开放安排与服务器时间为准</small>
    </div>
    <div class="toolbar">
      <label
        >预约日期<el-select v-model="date" aria-label="预约日期"
          ><el-option
            v-for="d in options"
            :key="d"
            :value="d"
            :label="d + (d === today() ? ' · 今天' : '')" /></el-select></label
      ><label
        >开始时间<el-select
          v-model="start"
          :disabled="!starts.length"
          aria-label="开始时间"
          @change="startChanged"
          ><el-option v-for="t in starts" :key="t" :value="t" /></el-select></label
      ><label
        >结束时间<el-select
          v-model="end"
          :disabled="!ends.length"
          aria-label="结束时间"
          @change="loadSeats()"
          ><el-option v-for="t in ends" :key="t" :value="t" /></el-select></label
      ><el-button
        :loading="loading"
        :disabled="!start || !end"
        type="primary"
        @click="refreshSchedules"
        >刷新座位</el-button
      ><span class="grow" /><el-checkbox v-model="socket">带插座</el-checkbox
      ><el-checkbox v-model="windowSeat">靠窗</el-checkbox>
    </div>
    <div class="booking-shortcuts">
      <span>常用时长</span
      ><el-button
        v-for="n in [30, 60, 120, 180, 240].filter((n) => n <= boot.rule.maxDurationMinutes)"
        :key="n"
        size="small"
        :type="end && minutes(end) - minutes(start) === n ? 'primary' : 'default'"
        :disabled="!start || minutes(start) + n > minutes(schedule?.closeTime || '00:00')"
        @click="duration(n)"
        >{{ n < 60 ? '30分钟' : n / 60 + '小时' }}</el-button
      ><span class="schedule-text"
        >{{
          schedule?.closed
            ? '当日闭馆'
            : schedule?.bookable
              ? '开放 ' + schedule.openTime.slice(0, 5) + '—' + schedule.closeTime.slice(0, 5)
              : '当日已无可预约时段'
        }}
        {{ schedule?.reason }}</span
      >
    </div>
  </section>
  <div class="room-tabs">
    <button
      v-for="r in rooms"
      :key="r.id"
      class="room-tab"
      :class="{ active: r.id === roomId }"
      :aria-pressed="r.id === roomId"
      @click="roomId = r.id"
    >
      <img
        class="room-thumb"
        :src="roomPhoto(r)"
        :alt="r.name + '环境示意图'"
        width="96"
        height="72"
      /><span class="room-tab-copy"
        ><strong>{{ r.name }}</strong
        ><small>{{ r.floor }} · {{ r.seatCount }} 个座位</small
        ><small>环境配图为示意图</small></span
      >
    </button>
  </div>
  <div class="seat-layout">
    <section class="panel seat-map-panel" aria-live="polite">
      <div class="panel-title">
        <h2><span class="step-number">2</span>{{ room?.name }}</h2>
        <span class="muted">符合条件 {{ available.length }} / {{ seats.length }}</span>
      </div>
      <div class="seat-tools">
        <p class="muted small">{{ room?.description }}</p>
        <div class="seat-search-tools">
          <el-input
            v-model="search"
            aria-label="搜索座位编号"
            placeholder="座位号，如 A-08"
            clearable
            maxlength="20"
          /><el-button size="small" :disabled="!available.length" @click="auto"
            >帮我选一个</el-button
          >
        </div>
      </div>
      <div v-if="queryError" role="alert" class="form-error">
        {{ queryError }}<el-button @click="refreshSchedules">重新加载</el-button>
      </div>
      <el-skeleton v-if="loading" :rows="5" animated />
      <template v-else
        ><p v-if="!seats.length" class="empty">
          {{ schedule?.closed ? '当日闭馆' : '没有可预约时段，请调整日期或阅览室。' }}
        </p>
        <p v-else-if="!available.length" class="match-count">
          暂无符合条件的可预约座位，请调整筛选。
        </p>
        <div class="room-annotation"><span>窗侧</span><span>阅览区域</span><span>窗侧</span></div>
        <div class="seat-map-scroll">
          <div
            class="seat-grid"
            :style="{ gridTemplateColumns: `repeat(${cols},minmax(42px,1fr))` }"
          >
            <button
              v-for="s in seats"
              :key="s.id"
              class="seat"
              :class="{
                unavailable: !s.available,
                selected: selected?.id === s.id,
                filtered: !matching.some((m) => m.id === s.id)
              }"
              :style="{ gridRow: s.rowNo, gridColumn: s.colNo }"
              :aria-label="
                s.seatNo + ' ' + (s.available ? '可预约' : s.enabled ? '不可用' : '停用')
              "
              :aria-disabled="!s.available"
              :aria-pressed="selected?.id === s.id"
              @click="choose(s)"
            >
              <b>{{ s.seatNo }}</b
              ><span>{{
                !s.available
                  ? s.enabled
                    ? '不可用'
                    : '停用'
                  : [s.hasSocket ? '插座' : '', s.nearWindow ? '靠窗' : '']
                      .filter(Boolean)
                      .join(' ')
              }}</span>
            </button>
          </div>
        </div>
        <div class="seat-map-bottom">
          <span>入口 / 出口</span>
          <div class="legend">
            <span><i />可预约</span><span><i class="taken" />不可用</span
            ><span><i class="chosen" />已选择</span>
          </div>
        </div></template
      >
    </section>
    <aside class="panel selection-panel">
      <h3><span class="step-number">3</span>确认预约</h3>
      <div class="selection-number">{{ selected?.seatNo || '请选择座位' }}</div>
      <dl class="detail-list">
        <div>
          <dt>阅览室</dt>
          <dd>{{ room?.name }}</dd>
        </div>
        <div>
          <dt>日期</dt>
          <dd>{{ date }}</dd>
        </div>
        <div>
          <dt>时段</dt>
          <dd>{{ start || '—' }}—{{ end || '—' }}</dd>
        </div>
        <div>
          <dt>预约时长</dt>
          <dd>{{ start && end ? minutes(end) - minutes(start) : 0 }} 分钟</dd>
        </div>
      </dl>
      <el-button type="primary" :disabled="!selected || !!restriction" @click="confirm = true"
        >确认预约</el-button
      >
      <div class="rule-note">
        <strong>预约须知 · 演示规则</strong>每人同时最多 1 条有效预约。<br />开始前
        {{ boot.rule.checkinEarlyMinutes }} 分钟可签到，开始后
        {{ boot.rule.checkinGraceMinutes }} 分钟截止。<br />暂离最多
        {{ boot.rule.leaveMinutes }} 分钟。
      </div>
    </aside>
  </div>
  <el-dialog
    v-model="confirm"
    title="确认预约"
    width="520px"
    :show-close="!busy"
    :close-on-click-modal="!busy"
    :close-on-press-escape="!busy"
    ><div class="selection-number">{{ selected?.seatNo }}</div>
    <p>{{ room?.name }} · {{ date }}</p>
    <p>{{ start }}—{{ end }}</p>
    <p class="muted">提交成功后才保留座位；网络重试复用同一请求标识。</p>
    <p v-if="message" role="alert" class="form-error">{{ message }}</p>
    <template #footer
      ><el-button :disabled="busy" @click="confirm = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="book">提交预约</el-button></template
    ></el-dialog
  >
</template>
