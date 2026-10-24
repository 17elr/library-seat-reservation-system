<script setup>
import { ref, reactive, nextTick } from 'vue'
import { api } from '../api'
import { errorText } from '../time'
const props = defineProps({ editor: Object })
const emit = defineEmits(['saved', 'close'])
const values = reactive({ ...props.editor.values }),
  errors = ref({}),
  message = ref(''),
  busy = ref(false),
  summary = ref()
function closed(name) {
  return ['openTime', 'closeTime'].includes(name) && values.isClosed
}
async function save() {
  message.value = ''
  errors.value = {}
  for (const f of props.editor.fields) {
    const v = values[f.name]
    if (f.required && !closed(f.name) && (v === undefined || v === null || String(v).trim() === ''))
      errors.value[f.name] = '请填写' + f.label
    if (f.type === 'password' && String(v || '').length < 8) errors.value[f.name] = '密码至少8位'
  }
  if (Object.keys(errors.value).length) {
    message.value = '请检查以下字段'
    await nextTick()
    summary.value?.focus()
    return
  }
  if (props.editor.confirm && !window.confirm(props.editor.confirm)) return
  busy.value = true
  try {
    const payload = { ...values }
    for (const f of props.editor.fields)
      if (f.type === 'number') payload[f.name] = Number(payload[f.name])
    await api(props.editor.path, { method: props.editor.method || 'POST', body: payload })
    emit('saved')
  } catch (e) {
    message.value = errorText(e)
    errors.value = e.errors || {}
    await nextTick()
    summary.value?.focus()
  } finally {
    busy.value = false
  }
}
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="editor.title"
    width="620px"
    :show-close="!busy"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    @close="!busy && emit('close')"
    ><form @submit.prevent="save" novalidate>
      <div v-if="message" ref="summary" role="alert" tabindex="-1" class="form-error">
        <b>{{ message }}</b
        ><a v-for="(error, key) in errors" :key="key" :href="'#field-' + key">{{ error }}</a>
      </div>
      <div class="edit-grid">
        <div v-for="f in editor.fields" :key="f.name" :class="{ full: f.type === 'textarea' }">
          <label :for="'field-' + f.name">{{ f.label }}<span v-if="f.required"> *</span></label>
          <select
            v-if="f.options"
            :id="'field-' + f.name"
            v-model="values[f.name]"
            :disabled="f.disabled"
          >
            <option v-for="o in f.options" :value="o.value" :key="o.value">{{ o.label }}</option>
          </select>
          <el-checkbox
            v-else-if="f.type === 'checkbox'"
            :id="'field-' + f.name"
            v-model="values[f.name]"
            >{{ f.label }}</el-checkbox
          >
          <textarea
            v-else-if="f.type === 'textarea'"
            :id="'field-' + f.name"
            v-model="values[f.name]"
            :maxlength="f.max || 5000"
            :aria-describedby="errors[f.name] ? 'error-' + f.name : undefined"
          />
          <input
            v-else
            :id="'field-' + f.name"
            v-model="values[f.name]"
            :type="f.type || 'text'"
            :disabled="f.disabled || closed(f.name)"
            :min="f.min"
            :max="f.type === 'number' ? f.max : undefined"
            :step="f.step"
            :maxlength="f.type === 'password' ? 64 : f.max || 200"
            :autocomplete="f.type === 'password' ? 'new-password' : 'off'"
            :aria-invalid="!!errors[f.name]"
            :aria-describedby="errors[f.name] ? 'error-' + f.name : undefined"
          />
          <small v-if="errors[f.name]" :id="'error-' + f.name" class="field-error">{{
            errors[f.name]
          }}</small>
        </div>
      </div>
      <p class="muted">{{ editor.help }}</p>
      <div class="form-actions">
        <el-button :disabled="busy" @click="emit('close')">取消</el-button
        ><el-button native-type="submit" type="primary" :loading="busy">保存</el-button>
      </div>
    </form></el-dialog
  >
</template>
