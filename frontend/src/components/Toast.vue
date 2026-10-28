<script setup>
import { useToast } from '../composables/useToast'
const toast = useToast()
const variantClass = (v) => v === 'info' ? '' : `toast-${v}`
</script>

<template>
  <div v-if="toast.items.length" class="toast-container">
    <div v-for="t in toast.items" :key="t.id"
         :class="['toast', variantClass(t.variant)]">
      <span class="toast-msg">{{ t.message }}</span>
      <button class="toast-close" @click="toast.dismiss(t.id)" aria-label="Close">✕</button>
    </div>
  </div>
</template>
