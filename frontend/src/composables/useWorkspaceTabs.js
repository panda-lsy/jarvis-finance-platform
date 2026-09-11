import { computed, ref, watch } from 'vue'

const BASE_TABS = ['行情', '多市场', '回测', '模拟盘', '研究助手', '多空研报', '财报解析', '产业链图谱', '风险预警', '策略生成', '运维']

export function useWorkspaceTabs(userRef) {
  const activeTab = ref('行情')
  const visitedTabs = ref(new Set(['行情']))
  const tabs = computed(() => userRef.value?.role === 'ADMIN' ? [...BASE_TABS, '管理'] : BASE_TABS)

  function reset() {
    activeTab.value = '行情'
    visitedTabs.value = new Set(['行情'])
  }

  function switchTab(name) {
    if (!tabs.value.includes(name)) return
    activeTab.value = name
    if (!visitedTabs.value.has(name)) {
      const next = new Set(visitedTabs.value)
      next.add(name)
      visitedTabs.value = next
    }
  }

  watch(tabs, available => {
    if (!available.includes(activeTab.value)) reset()
  })

  return {
    activeTab,
    visitedTabs,
    tabs,
    switchTab,
    reset,
  }
}
