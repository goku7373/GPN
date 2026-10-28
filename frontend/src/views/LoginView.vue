<script setup>
import { ref } from 'vue'
import { api } from '../api'

const emit = defineEmits(['login'])
const username = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const data = await api.login(username.value.trim(), password.value)
    emit('login', data.username)
  } catch (e) { error.value = e.message } finally { busy.value = false }
}
</script>

<template>
  <div class="login-wrap">
    <form class="card login-card anim-in" @submit.prevent="submit">
      <div class="brand">
        <div class="logo">G</div>
        <div>
          <h1>GPN</h1>
          <p class="muted">Sign in to manage exits and client credentials</p>
        </div>
      </div>
      <label class="field">
        <span>Admin username</span>
        <input class="input" v-model="username" autocomplete="username" autofocus />
      </label>
      <label class="field">
        <span>Password</span>
        <input class="input" v-model="password" type="password" autocomplete="current-password" />
      </label>
      <button class="btn btn-primary" type="submit" :disabled="busy || !username || !password">
        {{ busy ? 'Signing in…' : 'Sign in' }}
      </button>
      <p v-if="error" class="alert alert-error">{{ error }}</p>
      <p class="foot muted">VLESS + Reality · Encrypted exit · Per-device credentials</p>
    </form>
  </div>
</template>

<style scoped>
.login-wrap { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
.login-card { width: 380px; padding: var(--space-6) var(--space-5); display: flex; flex-direction: column; gap: var(--space-3); }
.brand { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-1); }
.logo {
  width: 40px; height: 40px;
  border-radius: var(--radius-md);
  display: grid; place-items: center;
  background: var(--color-text);
  color: var(--color-surface);
  font-size: 20px; font-weight: 800;
  flex-shrink: 0;
}
h1 { margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -0.2px; }
.btn-primary { margin-top: var(--space-1); padding: 8px; font-size: 13px; }
.foot { margin: 0; text-align: center; font-size: 11px; letter-spacing: .3px; }
</style>
