<script setup>
import { onMounted, ref } from 'vue'
import { api } from './api'
import LoginView from './views/LoginView.vue'
import App from './App.vue'

const username = ref('')
const loading = ref(true)

async function checkAuth() {
  try {
    const data = await api.me()
    username.value = data.username || ''
  } catch {
    username.value = ''
  } finally {
    loading.value = false
  }
}

function onLogin(name) {
  username.value = name
}

function onLogout() {
  username.value = ''
}

onMounted(checkAuth)
</script>

<template>
  <div v-if="loading" class="boot">Loading…</div>
  <LoginView v-else-if="!username" @login="onLogin" />
  <App v-else :username="username" @logout="onLogout" />
</template>
