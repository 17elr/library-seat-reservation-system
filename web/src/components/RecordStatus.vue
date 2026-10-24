<script setup>
import { statusNames } from '../time'
defineProps({ record: Object })
</script>
<template>
  <span class="status" :class="record.status">{{
    statusNames[record.status] || record.status
  }}</span
  ><small v-if="record.stateComputed" class="computed-note">按时间计算，待系统归档</small>
</template>
