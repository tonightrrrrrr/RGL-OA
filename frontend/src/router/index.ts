import { createRouter, createWebHashHistory } from 'vue-router'

// 使用 hash 路由：页面由后端静态托管，刷新时不会出现 404
export default createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/import' },
    { path: '/import', component: () => import('../views/ImportView.vue'), meta: { title: '导入识别' } },
    { path: '/review', component: () => import('../views/ReviewView.vue'), meta: { title: '待确认' } },
    { path: '/ledger', component: () => import('../views/LedgerView.vue'), meta: { title: '台账' } },
    { path: '/template', component: () => import('../views/TemplateView.vue'), meta: { title: '模板设置' } },
    { path: '/system', component: () => import('../views/SystemView.vue'), meta: { title: '系统设置' } },
    { path: '/ai', component: () => import('../views/AiView.vue'), meta: { title: 'AI 助手' } },
    { path: '/changelog', component: () => import('../views/ChangelogView.vue'), meta: { title: '版本更新' } }
  ]
})
