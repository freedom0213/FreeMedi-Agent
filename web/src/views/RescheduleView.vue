<script setup lang="ts">
import BookingView from './BookingView.vue'

defineProps<{ appointmentNo: string }>()
</script>

<template>
  <BookingView :key="appointmentNo" :original-number="appointmentNo" />
</template>
