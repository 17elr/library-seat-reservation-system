<script setup>
import { ref, reactive, watch, onUnmounted } from 'vue'
import { api } from '../api'
import { fmt, statusNames, errorText } from '../time'
import RecordStatus from '../components/RecordStatus.vue'
const props = defineProps({ admin: Boolean, kind: String })
const emit = defineEmits(['detail', 'editor'])
const filters = reactive({ status: '', date: '', search: '' }),
  page = ref(1),
  data = ref({ items: [], total: 0, size: 12 }),
  loading = ref(false),
  error = ref('')
let controller,
  version = 0
async function load() {
  const v = ++version
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  error.value = ''
  try {
    const path =
      props.kind === 'reservations'
        ? props.admin
          ? '/admin/reservations'
          : '/me/reservations'
        : props.admin
          ? '/admin/violations'
          : '/me/violations'
    const params = new URLSearchParams({
      page: page.value,
      ...Object.fromEntries(Object.entries(filters).filter(([, v]) => v))
    })
    const result = await api(path + '?' + params, { signal: controller.signal })
    if (v === version) data.value = result
  } catch (e) {
    if (e.name !== 'AbortError') error.value = errorText(e)
  } finally {
    if (v === version) loading.value = false
  }
}
watch(
  () => props.kind,
  () => {
    page.value = 1
    load()
  },
  { immediate: true }
)
onUnmounted(() => controller?.abort())
function resetFilters() {
  Object.assign(filters, { status: '', date: '', search: '' })
  query()
}
function query() {
  page.value = 1
  load()
}
function operation(row, action) {
  emit('editor', {
    title: action === 'close' ? '关闭预约' : '撤销违约',
    path: `/admin/${props.kind}/${row.id}/${action}`,
    values: { reason: '' },
    fields: [{ name: 'reason', label: '操作原因', type: 'textarea', required: true, max: 200 }],
    confirm:
      action === 'close'
        ? '确定关闭此预约并释放余下时段吗？'
        : '确定撤销违约并重新计算预约限制吗？',
    help: '保留原记录和操作日志。'
  })
}
defineExpose({ load })
</script>
<template>
  <div class="heading">
    <div>
      <h1>
        {{
          kind === 'reservations'
            ? admin
              ? '预约管理'
              : '我的预约'
            : admin
              ? '违约管理'
              : '违约记录'
        }}
      </h1>
      <p>按条件查看记录，核对状态与操作原因。</p>
    </div>
  </div>
  <section class="panel">
    <form v-if="kind === 'reservations'" class="toolbar" @submit.prevent="query">
      <label
        >预约状态<el-select v-model="filters.status" aria-label="预约状态"
          ><el-option label="全部状态" value="" /><el-option
            label="进行中的预约"
            value="ACTIVE" /><el-option
            v-for="(label, key) in statusNames"
            :key="key"
            :label="label"
            :value="key" /></el-select></label
      ><label>预约日期<input type="date" v-model="filters.date" /></label
      ><label v-if="admin"
        >学号 / 姓名 / 编号<el-input
          v-model="filters.search"
          placeholder="搜索预约"
          maxlength="100"
          clearable /></label
      ><el-button type="primary" native-type="submit">查询</el-button
      ><el-button @click="resetFilters">重置</el-button>
    </form>
    <p v-if="error" role="alert" class="form-error">
      {{ error }}<el-button @click="load">重试</el-button>
    </p>
    <el-table :data="data.items" v-loading="loading" empty-text="暂无符合条件的记录"
      ><el-table-column v-if="admin" label="学生" min-width="130"
        ><template #default="{ row }"
          >{{ row.userName }}<small class="cell-note">{{ row.studentNo }}</small></template
        ></el-table-column
      >
      <template v-if="kind === 'reservations'"
        ><el-table-column label="预约编号" prop="reservationNo" min-width="180" /><el-table-column
          label="阅览室 / 座位"
          min-width="150"
          ><template #default="{ row }"
            >{{ row.roomName }}<small class="cell-note">{{ row.seatNo }}</small></template
          ></el-table-column
        ><el-table-column label="预约时段" min-width="200"
          ><template #default="{ row }"
            >{{ fmt(row.startTime) }} — {{ row.endTime.slice(11, 16) }}</template
          ></el-table-column
        ><el-table-column label="状态" min-width="150"
          ><template #default="{ row }"><RecordStatus :record="row" /></template></el-table-column
      ></template>
      <template v-else
        ><el-table-column label="违约类型" min-width="120"
          ><template #default="{ row }">{{ statusNames[row.type] }}</template></el-table-column
        ><el-table-column label="原因" min-width="200"
          ><template #default="{ row }"
            >{{ row.reason
            }}<small v-if="row.isRevoked" class="cell-note"
              >撤销原因：{{ row.revokeReason }}</small
            ></template
          ></el-table-column
        ><el-table-column label="发生时间" min-width="160"
          ><template #default="{ row }">{{ fmt(row.occurredAt) }}</template></el-table-column
        ><el-table-column label="状态"
          ><template #default="{ row }">{{
            row.isRevoked ? '已撤销' : '有效'
          }}</template></el-table-column
        ></template
      >
      <el-table-column label="操作" min-width="155" fixed="right"
        ><template #default="{ row }"
          ><el-button
            size="small"
            @click="emit('detail', kind === 'reservations' ? row.id : row.reservationId)"
            >详情</el-button
          ><el-button
            v-if="
              admin &&
              (kind === 'reservations'
                ? ['RESERVED', 'CHECKED_IN', 'TEMP_LEFT'].includes(row.status)
                : !row.isRevoked)
            "
            size="small"
            @click="operation(row, kind === 'reservations' ? 'close' : 'revoke')"
            >{{ kind === 'reservations' ? '关闭' : '撤销' }}</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-pagination
      v-model:current-page="page"
      :page-size="data.size"
      :total="data.total"
      layout="total, prev, pager, next"
      @current-change="load"
    />
  </section>
</template>
