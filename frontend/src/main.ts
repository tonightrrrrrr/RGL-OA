import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { installRipple } from './utils/ripple'
import './style.css'

createApp(App).use(createPinia()).use(router).mount('#app')
installRipple()
