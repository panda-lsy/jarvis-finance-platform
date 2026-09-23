<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import NewsSourceAdminPanel from './NewsSourceAdminPanel.vue'

const props = defineProps({
  currentUserId: { type: [Number, String], default: null },
})

const users = ref([])
const groups = ref([])
const selected = ref(null)
const selectedGroup = ref(null)
const query = ref('')
const loading = ref(false)
const initialLoading = ref(true)
const selectedLoading = ref(false)
const message = ref('')
const error = ref('')
const usersError = ref('')
const groupsError = ref('')
const audit = ref([])
const accountActionReason = ref('')
const roleReason = ref('')
const roleDraft = ref('USER')
const quota = reactive({ dailyRequestLimit: 100, monthlyTokenLimit: 0, reason: '' })
const permissions = ref('')
const permissionReason = ref('')
const groupName = ref('')
const groupDescription = ref('')
const groupInfoReason = ref('')
const groupMembers = ref([])
const groupMembersReason = ref('')
const groupDeleteReason = ref('')
const groupPermissions = ref('')
const groupQuota = reactive({ dailyRequestLimit: 100, monthlyTokenLimit: 0, reason: '' })
const groupPermissionReason = ref('')
const groupLoading = ref(false)
const groupSaving = ref(false)

function startNewGroup() {
  selectedGroup.value = null
  groupName.value = ''
  groupDescription.value = ''
  groupInfoReason.value = ''
  groupMembers.value = []
  groupMembersReason.value = ''
  groupDeleteReason.value = ''
  groupPermissions.value = ''
  Object.assign(groupQuota, { dailyRequestLimit: 100, monthlyTokenLimit: 0, reason: '' })
  groupPermissionReason.value = ''
}
const adminCount = computed(() => users.value.filter(user => user.role === 'ADMIN').length)
const enabledCount = computed(() => users.value.filter(user => user.enabled).length)

function showError(value) {
  error.value = value?.message || value || '操作失败'
  message.value = ''
}

async function loadUsers() {
  loading.value = true
  error.value = ''
  usersError.value = ''
  try {
    const response = await api.adminUsers(query.value.trim())
    if (response.code !== 200) throw new Error(response.message || '用户列表加载失败')
    users.value = response.data?.items || []
    if (selected.value && !users.value.some(u => u.id === selected.value.id)) selected.value = null
  } catch (e) {
    usersError.value = e?.message || String(e)
    message.value = ''
  }
  finally {
    loading.value = false
    initialLoading.value = false
  }
}

async function loadGroups() {
  groupsError.value = ''
  try {
    const response = await api.adminGroups()
    if (response.code !== 200) throw new Error(response.message || '用户组加载失败')
    groups.value = response.data?.items || []
    if (selectedGroup.value && !groups.value.some(group => group.id === selectedGroup.value.id)) {
      selectedGroup.value = null
    }
  } catch (e) {
    groupsError.value = e?.message || String(e)
  }
}

async function selectGroup(group) {
  groupLoading.value = true
  try {
    const response = await api.adminGroup(group.id)
    if (response.code !== 200) throw new Error(response.message || '用户组详情加载失败')
    selectedGroup.value = response.data
    groupName.value = response.data.name || ''
    groupDescription.value = response.data.description || ''
    groupInfoReason.value = ''
    groupMembers.value = (response.data.members || []).map(member => member.id)
    groupMembersReason.value = ''
    groupDeleteReason.value = ''
    groupPermissions.value = (response.data.permissions || []).join(', ')
    Object.assign(groupQuota, {
      dailyRequestLimit: response.data.quota?.dailyRequestLimit ?? 100,
      monthlyTokenLimit: response.data.quota?.monthlyTokenLimit ?? 0,
      reason: '',
    })
    groupPermissionReason.value = ''
  } catch (e) { showError(e) }
  finally { groupLoading.value = false }
}

async function createGroup() {
  if (!groupName.value.trim() || !groupInfoReason.value.trim()) {
    showError('请填写用户组名称和操作原因')
    return
  }
  groupSaving.value = true
  try {
    const response = await api.adminCreateGroup({
      name: groupName.value.trim(), description: groupDescription.value.trim(),
      reason: groupInfoReason.value.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '创建用户组失败')
    await loadGroups()
    await selectGroup(response.data)
    message.value = '用户组已创建'
  } catch (e) { showError(e) }
  finally { groupSaving.value = false }
}

async function updateGroup() {
  if (!selectedGroup.value || !groupName.value.trim() || !groupInfoReason.value.trim()) {
    showError('请填写用户组名称和操作原因')
    return
  }
  groupSaving.value = true
  try {
    const response = await api.adminUpdateGroup(selectedGroup.value.id, {
      name: groupName.value.trim(), description: groupDescription.value.trim(),
      reason: groupInfoReason.value.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '用户组更新失败')
    await loadGroups()
    await selectGroup(response.data)
    message.value = '用户组信息已更新'
  } catch (e) { showError(e) }
  finally { groupSaving.value = false }
}

async function saveGroupMembers() {
  if (!selectedGroup.value || !groupMembersReason.value.trim()) {
    showError('请填写用户组成员调整原因')
    return
  }
  groupSaving.value = true
  try {
    const response = await api.adminUpdateGroupMembers(selectedGroup.value.id, {
      userIds: groupMembers.value.map(Number), reason: groupMembersReason.value.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '组成员更新失败')
    await loadGroups()
    await selectGroup(response.data)
    message.value = '用户组成员已更新'
  } catch (e) { showError(e) }
  finally { groupSaving.value = false }
}

async function updateGroupQuota() {
  if (!selectedGroup.value || !groupQuota.reason.trim()) {
    showError('请填写用户组配额调整原因')
    return
  }
  groupSaving.value = true
  try {
    const response = await api.adminUpdateGroupQuota(selectedGroup.value.id, {
      dailyRequestLimit: Number(groupQuota.dailyRequestLimit),
      monthlyTokenLimit: Number(groupQuota.monthlyTokenLimit), reason: groupQuota.reason.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '用户组配额更新失败')
    await loadGroups()
    await selectGroup(response.data)
    message.value = '用户组配额已更新'
  } catch (e) { showError(e) }
  finally { groupSaving.value = false }
}

async function updateGroupPermissions() {
  if (!selectedGroup.value || !groupPermissionReason.value.trim()) {
    showError('请填写用户组权限调整原因')
    return
  }
  groupSaving.value = true
  try {
    const response = await api.adminUpdateGroupPermissions(selectedGroup.value.id, {
      features: groupPermissions.value.split(',').map(v => v.trim()).filter(Boolean),
      reason: groupPermissionReason.value.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '用户组权限更新失败')
    await loadGroups()
    await selectGroup(response.data)
    message.value = '用户组权限已更新'
  } catch (e) { showError(e) }
  finally { groupSaving.value = false }
}

async function deleteGroup() {
  if (!selectedGroup.value || !groupDeleteReason.value.trim()) {
    showError('请填写删除用户组的原因')
    return
  }
  if (!window.confirm(`确认删除用户组“${selectedGroup.value.name}”？此操作会记录审计事件。`)) return
  groupSaving.value = true
  try {
    const response = await api.adminDeleteGroup(selectedGroup.value.id, groupDeleteReason.value.trim())
    if (response.code !== 200) throw new Error(response.message || '用户组删除失败')
    startNewGroup()
    await loadGroups()
    message.value = '用户组已删除'
  } catch (e) { showError(e) }
  finally { groupSaving.value = false }
}

async function selectUser(user) {
  error.value = ''
  selectedLoading.value = true
  try {
    const response = await api.adminUser(user.id)
    if (response.code !== 200) throw new Error(response.message || '用户详情加载失败')
    selected.value = response.data
    roleDraft.value = response.data.role || 'USER'
    accountActionReason.value = ''
    roleReason.value = ''
    try {
      const auditResponse = await api.adminUserAudit(user.id)
      audit.value = auditResponse.code === 200 ? (auditResponse.data || []) : []
    } catch (_) {
      audit.value = []
    }
    Object.assign(quota, {
      dailyRequestLimit: response.data.quota?.dailyRequestLimit ?? 100,
      monthlyTokenLimit: response.data.quota?.monthlyTokenLimit ?? 0,
      reason: '',
    })
    permissions.value = (response.data.permissions || []).join(', ')
    permissionReason.value = ''
  } catch (e) { showError(e) }
  finally { selectedLoading.value = false }
}

async function updateStatus() {
  if (!selected.value || !accountActionReason.value.trim()) {
    showError('请填写账户操作原因')
    return
  }
  try {
    const response = await api.adminUpdateStatus(
      selected.value.id, !selected.value.enabled, accountActionReason.value.trim(),
    )
    if (response.code !== 200) throw new Error(response.message || '账户状态更新失败')
    selected.value = { ...selected.value, ...response.data }
    accountActionReason.value = ''
    message.value = '账户状态已更新'
    await loadUsers()
    await selectUser(selected.value)
  } catch (e) { showError(e) }
}

async function updateRole() {
  if (!selected.value || !roleReason.value.trim()) {
    showError('请填写角色变更原因')
    return
  }
  try {
    const response = await api.adminUpdateRole(selected.value.id, roleDraft.value, roleReason.value.trim())
    if (response.code !== 200) throw new Error(response.message || '角色更新失败')
    selected.value = { ...selected.value, ...response.data }
    roleDraft.value = response.data.role || roleDraft.value
    roleReason.value = ''
    message.value = '账户角色已更新'
    await loadUsers()
    await selectUser(selected.value)
  } catch (e) { showError(e) }
}

async function revokeSessions() {
  if (!selected.value || !accountActionReason.value.trim()) {
    showError('请填写重置登录状态的原因')
    return
  }
  if (Number(selected.value.id) === Number(props.currentUserId)) {
    showError('不能重置当前管理员自己的登录状态')
    return
  }
  if (!window.confirm(`撤销 ${selected.value.email} 的所有现有登录令牌；不修改密码。是否继续？`)) return
  try {
    const response = await api.adminRevokeSessions(selected.value.id, {
      reason: accountActionReason.value.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '重置登录状态失败')
    accountActionReason.value = ''
    message.value = '该账户的现有登录令牌已撤销，需重新登录'
    await selectUser(selected.value)
  } catch (e) { showError(e) }
}

async function updateQuota() {
  if (!selected.value || !quota.reason.trim()) {
    showError('请填写配额调整原因')
    return
  }
  try {
    const response = await api.adminUpdateQuota(selected.value.id, {
      dailyRequestLimit: Number(quota.dailyRequestLimit),
      monthlyTokenLimit: Number(quota.monthlyTokenLimit),
      reason: quota.reason.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '配额更新失败')
    await selectUser(selected.value)
    message.value = 'AI 配额已更新'
  } catch (e) { showError(e) }
}

async function updatePermissions() {
  if (!selected.value || !permissionReason.value.trim()) {
    showError('请填写功能权限调整原因')
    return
  }
  try {
    const response = await api.adminUpdatePermissions(selected.value.id, {
      features: permissions.value.split(',').map(v => v.trim()).filter(Boolean),
      reason: permissionReason.value.trim(),
    })
    if (response.code !== 200) throw new Error(response.message || '权限更新失败')
    selected.value = response.data
    permissionReason.value = ''
    await selectUser(selected.value)
    message.value = '功能权限已更新'
  } catch (e) { showError(e) }
}

onMounted(async () => {
  await Promise.all([loadUsers(), loadGroups()])
})
</script>

<template>
  <div class="admin-view">
    <div class="admin-head">
      <div><h2>访问控制</h2><span>账户状态、角色、研究配额与功能权限</span></div>
      <div class="admin-stats">
        <span>用户 <b>{{ users.length }}</b></span>
        <span>启用 <b>{{ enabledCount }}</b></span>
        <span>管理员 <b>{{ adminCount }}</b></span>
      </div>
    </div>

    <div class="admin-toolbar">
      <input v-model="query" class="search-input" aria-label="搜索用户" placeholder="搜索邮箱或昵称" @keyup.enter="loadUsers" />
      <button type="button" class="btn" @click="loadUsers" :disabled="loading">{{ loading ? '加载中…' : '搜索' }}</button>
    </div>

    <div v-if="error" class="notice error" role="alert">{{ error }}</div>
    <div v-if="message" class="notice success" role="status" aria-live="polite">{{ message }}</div>

    <div class="admin-layout">
      <aside class="directory-panel">
        <div class="directory-head"><b>用户目录</b><span>按注册时间倒序</span></div>
        <DataState v-if="initialLoading" state="loading" title="正在加载用户目录" compact />
        <DataState v-else-if="usersError && !users.length" state="error" title="用户目录加载失败" :message="usersError" compact retryable @retry="loadUsers" />
        <div v-else class="user-list" role="listbox" aria-label="用户目录">
          <button v-for="user in users" :key="user.id" type="button" class="user-row"
                  role="option" :aria-selected="selected?.id === user.id"
                  :class="{ active: selected?.id === user.id }" @click="selectUser(user)">
            <span class="identity">
              <b>{{ user.displayName || user.email }}</b>
              <small>{{ user.email }}</small>
            </span>
            <span class="user-meta">
              <em :class="user.role === 'ADMIN' ? 'admin' : ''">{{ user.role }}</em>
              <i :class="user.enabled ? 'ok' : 'bad'"></i>
            </span>
          </button>
          <DataState v-if="!users.length" state="empty" title="没有匹配用户" message="调整搜索条件后重试。" compact />
        </div>
      </aside>

      <DataState v-if="selectedLoading && !selected" state="loading" title="正在加载账户详情" />
      <main v-else-if="selected" class="user-detail" :aria-busy="selectedLoading">
        <section class="detail-panel account-panel">
          <div class="detail-head">
            <div>
              <h3>{{ selected.displayName || selected.email }}</h3>
              <span>{{ selected.email }}</span>
            </div>
            <div class="account-actions">
              <span class="status-label" :class="selected.enabled ? 'ok' : 'bad'"><i></i>{{ selected.enabled ? 'ENABLED' : 'DISABLED' }}</span>
              <button type="button" class="btn danger" :disabled="Number(selected.id) === Number(props.currentUserId) && selected.enabled || !accountActionReason.trim()" @click="updateStatus">{{ selected.enabled ? '禁用账户' : '重新启用' }}</button>
            </div>
          </div>

          <div class="account-meta">
            <div><span>USER ID</span><b>#{{ selected.id }}</b></div>
            <div><span>角色</span><b>{{ selected.role }}</b></div>
            <div><span>注册时间</span><b>{{ selected.createdAt?.replace('T', ' ').slice(0, 19) || '-' }}</b></div>
            <div><span>最近登录</span><b>{{ selected.lastLoginAt?.replace('T', ' ').slice(0, 19) || '从未登录' }}</b></div>
            <div><span>登录方式</span><b>{{ selected.authProviders?.join(' / ') || '邮箱' }}</b></div>
            <div><span>功能权限</span><b>{{ selected.permissions?.length || 0 }} 项</b></div>
            <div><span>用户组</span><b>{{ selected.group?.name || '未分组' }}</b></div>
          </div>

          <div class="account-mutation-row">
            <input v-model="accountActionReason" class="input" aria-label="账户操作原因" placeholder="账户操作原因（必填，用于审计）" />
            <button type="button" class="btn" :disabled="!accountActionReason.trim() || Number(selected.id) === Number(props.currentUserId)" @click="revokeSessions">重置登录状态</button>
            <small>撤销全部已签发登录令牌，不修改密码；用户下次请求需重新登录。</small>
          </div>

          <label class="role-control">
            <span>账户角色</span>
            <select v-model="roleDraft" class="select">
              <option value="USER">普通用户 USER</option>
              <option value="ADMIN">管理员 ADMIN</option>
            </select>
            <input v-model="roleReason" class="input" aria-label="角色变更原因" placeholder="角色变更原因（必填）" />
            <button type="button" class="btn" :disabled="!roleReason.trim() || Number(selected.id) === Number(props.currentUserId) && roleDraft !== 'ADMIN'" @click="updateRole">保存角色</button>
          </label>
        </section>

        <div class="control-grid">
          <section class="detail-panel">
            <div class="section-title"><div><b>研究配额</b><span>限制研究服务的资源使用</span></div></div>
            <div class="quota-usage">
              <div><span>今日请求</span><b>{{ selected.quota?.dailyRequestUsed ?? selected.group?.quota?.dailyRequestUsed ?? 0 }} / {{ selected.quota?.dailyRequestLimit ?? selected.group?.quota?.dailyRequestLimit ?? quota.dailyRequestLimit }}</b></div>
              <div><span>本月 Token</span><b>{{ selected.quota?.monthlyTokenUsed ?? selected.group?.quota?.monthlyTokenUsed ?? 0 }} / {{ selected.quota?.monthlyTokenLimit ?? selected.group?.quota?.monthlyTokenLimit ?? quota.monthlyTokenLimit }}</b></div>
            </div>
            <div class="form-grid">
              <label><span>每日请求上限</span><input v-model.number="quota.dailyRequestLimit" class="num" type="number" min="0" /></label>
              <label><span>月 Token 上限</span><input v-model.number="quota.monthlyTokenLimit" class="num" type="number" min="0" /></label>
            </div>
            <input v-model="quota.reason" class="input full" aria-label="配额调整原因" placeholder="调整原因（必填，用于审计）" />
            <button type="button" class="btn primary" @click="updateQuota">保存配额</button>
          </section>

          <section class="detail-panel">
            <div class="section-title"><div><b>功能权限</b><span>按 feature key 控制高级功能</span></div></div>
            <div class="permission-preview">
              <span v-for="feature in selected.permissions || []" :key="feature">{{ feature }}</span>
              <em v-if="!selected.permissions?.length">当前未配置额外权限</em>
            </div>
            <textarea v-model="permissions" class="permission-input" aria-label="功能权限列表" placeholder="AI_CHAT, MARKET_ADVANCED"></textarea>
            <div class="permission-hint">多个权限使用逗号分隔。保存后会替换该用户当前的功能权限集合。</div>
            <input v-model="permissionReason" class="input full" aria-label="功能权限调整原因" placeholder="调整原因（必填，用于审计）" />
            <button type="button" class="btn primary" :disabled="!permissionReason.trim()" @click="updatePermissions">保存权限</button>
          </section>
        </div>

        <section class="detail-panel audit-panel">
          <div class="section-title"><div><b>最近审计事件</b><span>管理员只读查看该账户的关键操作</span></div><span>{{ audit.length }} 条</span></div>
          <div v-if="audit.length" class="audit-list">
            <div v-for="event in audit.slice(0, 12)" :key="event.id" class="audit-row">
              <b>{{ event.action }}</b><span>{{ event.detail || event.target || '—' }}</span><time>{{ event.createdAt?.replace('T', ' ').slice(0, 19) || '—' }}</time>
            </div>
          </div>
          <div v-else class="audit-empty">暂无审计事件</div>
        </section>
      </main>

      <div v-else class="empty-detail">
        <div class="empty-mark">ACL</div>
        <b>选择用户</b>
        <span>从左侧目录选择账户以查看角色、配额和功能权限。</span>
      </div>
    </div>

    <section class="group-workspace">
      <div class="group-directory detail-panel">
        <div class="section-title"><div><b>用户组策略</b><span>组策略是未设置用户级覆盖时的默认额度与权限</span></div><div class="group-head-actions"><span>{{ groups.length }} 组</span><button type="button" class="btn" @click="startNewGroup">新建</button></div></div>
        <div v-if="groupsError" class="group-error">{{ groupsError }}</div>
        <div class="group-create-form">
          <input v-model="groupName" class="input" aria-label="用户组名称" placeholder="新用户组名称" />
          <input v-model="groupDescription" class="input" aria-label="用户组描述" placeholder="描述（可选）" />
          <input v-model="groupInfoReason" class="input" aria-label="用户组信息调整原因" placeholder="操作原因（必填，用于审计）" />
          <button v-if="!selectedGroup" type="button" class="btn primary" :disabled="groupSaving || !groupInfoReason.trim()" @click="createGroup">创建用户组</button>
          <button v-else type="button" class="btn" :disabled="groupSaving || !groupInfoReason.trim()" @click="updateGroup">保存组信息</button>
        </div>
        <div class="group-list" role="listbox" aria-label="用户组目录">
          <button v-for="group in groups" :key="group.id" type="button" class="group-row"
                  role="option" :aria-selected="selectedGroup?.id === group.id"
                  :class="{ active: selectedGroup?.id === group.id }" @click="selectGroup(group)">
            <span><b>{{ group.name }}</b><small>{{ group.description || '未填写描述' }}</small></span>
            <em>{{ group.memberCount || 0 }} 人</em>
          </button>
          <span v-if="!groups.length" class="group-empty">暂无用户组，可在上方创建。</span>
        </div>
      </div>

      <div v-if="selectedGroup" class="group-detail detail-panel" :aria-busy="groupLoading || groupSaving">
        <div class="detail-head">
          <div><h3>{{ selectedGroup.name }}</h3><span>组级策略 · 用户级覆盖优先</span></div>
          <div class="group-delete-control">
            <input v-model="groupDeleteReason" class="input" aria-label="删除用户组原因" placeholder="删除原因（必填）" />
            <button type="button" class="btn danger" :disabled="groupSaving || !groupDeleteReason.trim()" @click="deleteGroup">删除用户组</button>
          </div>
        </div>
        <div class="group-controls">
          <section class="group-card">
            <div class="section-title"><div><b>成员分配</b><span>一个用户同一时间只属于一个策略组</span></div></div>
            <div class="member-checks">
              <label v-for="user in users" :key="user.id" class="member-check">
                <input v-model="groupMembers" type="checkbox" :value="user.id" />
                <span>{{ user.displayName || user.email }}</span>
              </label>
            </div>
            <input v-model="groupMembersReason" class="input full" aria-label="用户组成员调整原因" placeholder="成员调整原因（必填，用于审计）" />
            <button type="button" class="btn primary" :disabled="groupSaving || !groupMembersReason.trim()" @click="saveGroupMembers">保存成员</button>
          </section>

          <section class="group-card">
            <div class="section-title"><div><b>共享 AI 配额</b><span>组内成员共享计数；用户级配额优先</span></div></div>
            <div class="quota-usage">
              <div><span>今日请求</span><b>{{ selectedGroup.quota?.dailyRequestUsed ?? 0 }} / {{ selectedGroup.quota?.dailyRequestLimit ?? groupQuota.dailyRequestLimit }}</b></div>
              <div><span>本月 Token</span><b>{{ selectedGroup.quota?.monthlyTokenUsed ?? 0 }} / {{ selectedGroup.quota?.monthlyTokenLimit ?? groupQuota.monthlyTokenLimit }}</b></div>
            </div>
            <div class="form-grid">
              <label><span>每日请求上限</span><input v-model.number="groupQuota.dailyRequestLimit" class="num" type="number" min="0" /></label>
              <label><span>月 Token 上限</span><input v-model.number="groupQuota.monthlyTokenLimit" class="num" type="number" min="0" /></label>
            </div>
            <input v-model="groupQuota.reason" class="input full" aria-label="用户组配额调整原因" placeholder="调整原因（必填，用于审计）" />
            <button type="button" class="btn primary" :disabled="groupSaving" @click="updateGroupQuota">保存组配额</button>
          </section>

          <section class="group-card">
            <div class="section-title"><div><b>组级功能权限</b><span>没有用户级覆盖时生效</span></div></div>
            <div class="permission-preview">
              <span v-for="feature in selectedGroup.permissions || []" :key="feature">{{ feature }}</span>
              <em v-if="!selectedGroup.permissions?.length">当前未配置组级权限</em>
            </div>
            <textarea v-model="groupPermissions" class="permission-input" aria-label="用户组功能权限列表" placeholder="AI_REPORT, AI_RISK"></textarea>
            <input v-model="groupPermissionReason" class="input full" aria-label="用户组权限调整原因" placeholder="调整原因（必填，用于审计）" />
            <button type="button" class="btn primary" :disabled="groupSaving" @click="updateGroupPermissions">保存组权限</button>
          </section>
        </div>
      </div>
    </section>

    <NewsSourceAdminPanel />
  </div>
</template>

<style scoped>
.admin-view { display: flex; flex-direction: column; gap: 10px; }
.admin-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.admin-head h2 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; }
.admin-head > div:first-child > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.admin-stats { display: flex; align-items: center; gap: 14px; color: var(--subtle); font-size: 9px; }
.admin-stats b { margin-left: 4px; color: var(--text); font-size: 11px; font-weight: 650; font-variant-numeric: tabular-nums; }
.admin-toolbar { display: flex; align-items: center; gap: 7px; }
.search-input, .input, .select, .num, .permission-input { background: var(--surface); border: 1px solid var(--line-strong); color: var(--text); border-radius: var(--radius-sm); outline: none; }
.search-input { width: min(360px, 100%); height: 32px; padding: 0 9px; font-size: 10px; }
.search-input:focus, .input:focus, .select:focus, .num:focus, .permission-input:focus { border-color: #695b40; }
.btn { border: 1px solid var(--line-strong); background: #1c1f22; color: var(--text); border-radius: var(--radius-sm); padding: 6px 11px; cursor: pointer; font-size: 10px; }
.btn.primary { background: var(--accent); border-color: var(--accent); color: #17140e; font-weight: 700; }
.btn.danger { border-color: #55383a; background: rgba(239,83,80,.05); color: #d97774; }
.btn:disabled { opacity: .45; cursor: not-allowed; }
.notice { padding: 8px 9px; border-radius: var(--radius-sm); font-size: 9px; }
.notice.success { border: 1px solid rgba(39,196,107,.18); background: rgba(39,196,107,.07); color: #67d69a; }
.notice.error { border: 1px solid rgba(239,83,80,.18); background: rgba(239,83,80,.07); color: #e47d79; }
.admin-layout { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 10px; align-items: stretch; min-width: 0; }
.directory-panel, .detail-panel, .empty-detail { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); }
.directory-panel { padding: 11px; min-width: 0; }
.directory-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; padding-bottom: 9px; border-bottom: 1px solid var(--line); }
.directory-head b { color: var(--text); font-size: 11px; font-weight: 650; }
.directory-head span { color: var(--subtle); font-size: 8px; }
.user-list { display: flex; flex-direction: column; gap: 3px; max-height: 590px; overflow: auto; margin-top: 8px; }
.user-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; border: 1px solid transparent; background: transparent; color: var(--text); border-radius: 3px; padding: 8px; cursor: pointer; text-align: left; }
.user-row:hover { background: #1a1d20; }
.user-row.active { border-color: #5f523a; background: rgba(201,166,95,.065); }
.identity { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.identity b, .identity small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.identity b { color: var(--text); font-size: 10px; font-weight: 600; }
.identity small { color: var(--subtle); font-size: 8px; }
.user-meta { display: flex; align-items: center; gap: 6px; flex: 0 0 auto; }
.user-meta em { color: var(--subtle); border: 1px solid #30343a; border-radius: 2px; padding: 1px 4px; font-size: 7px; font-style: normal; letter-spacing: .04em; }
.user-meta em.admin { color: var(--accent-strong); border-color: #51462f; }
.user-meta i { width: 5px; height: 5px; border-radius: 50%; background: var(--bad); }
.user-meta i.ok { background: var(--ok); }
.empty-list { padding: 18px 8px; text-align: center; color: var(--subtle); font-size: 9px; }
.user-detail { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.detail-panel { padding: 13px; min-width: 0; }
.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-head h3 { margin: 0; color: var(--text); font-size: 14px; font-weight: 680; }
.detail-head > div:first-child > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.account-actions { display: flex; align-items: center; gap: 8px; }
.account-mutation-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 7px; margin-top: 10px; }
.account-mutation-row small { grid-column: 1 / -1; color: var(--subtle); font-size: 8px; }
.status-label { display: inline-flex; align-items: center; gap: 5px; color: var(--muted); font-size: 8px; letter-spacing: .05em; }
.status-label i { width: 5px; height: 5px; border-radius: 50%; background: var(--bad); }
.status-label.ok { color: #67c98e; }
.status-label.ok i { background: var(--ok); }
.status-label.bad { color: #e47d79; }
.account-meta { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 1px; margin-top: 13px; border: 1px solid var(--line); background: var(--line); border-radius: var(--radius-sm); overflow: hidden; }
.account-meta > div { padding: 8px 9px; background: var(--surface); min-width: 0; }
.account-meta span { display: block; color: var(--subtle); font-size: 7px; letter-spacing: .05em; }
.account-meta b { display: block; margin-top: 4px; color: var(--text); font-size: 9px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.role-control { display: grid; grid-template-columns: auto minmax(130px, .8fr) minmax(150px, 2fr) auto; align-items: center; gap: 8px; margin-top: 12px; }
.role-control > span { color: var(--muted); font-size: 9px; }
.select { height: 31px; padding: 0 8px; font-size: 9px; }
.control-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.section-title { display: flex; align-items: center; justify-content: space-between; padding-bottom: 9px; border-bottom: 1px solid var(--line); }
.section-title > div { display: flex; flex-direction: column; gap: 3px; }
.section-title b { color: var(--text); font-size: 11px; font-weight: 650; }
.section-title span { color: var(--subtle); font-size: 8px; }
.quota-usage { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; margin-top: 10px; }
.quota-usage > div { padding: 8px 9px; background: var(--surface); border: 1px solid #222529; border-radius: var(--radius-sm); }
.quota-usage span { display: block; color: var(--subtle); font-size: 8px; }
.quota-usage b { display: block; margin-top: 4px; color: var(--text); font-size: 10px; font-weight: 600; font-variant-numeric: tabular-nums; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
.form-grid label { display: flex; flex-direction: column; gap: 5px; }
.form-grid label span { color: var(--muted); font-size: 8px; }
.num { width: 100%; height: 31px; padding: 0 8px; font-size: 9px; }
.input.full { width: 100%; height: 32px; margin: 9px 0; padding: 0 8px; font-size: 9px; }
.permission-preview { display: flex; flex-wrap: wrap; gap: 5px; min-height: 36px; margin-top: 10px; }
.permission-preview span { color: var(--accent-strong); border: 1px solid #51462f; background: rgba(201,166,95,.04); border-radius: 3px; padding: 3px 5px; font-size: 8px; }
.permission-preview em { color: var(--subtle); font-size: 9px; font-style: normal; }
.permission-input { width: 100%; min-height: 74px; margin-top: 7px; padding: 8px; resize: vertical; font-size: 9px; line-height: 1.5; }
.permission-hint { margin: 6px 0 9px; color: var(--subtle); font-size: 8px; line-height: 1.5; }
.audit-panel { margin-top: 0; }
.audit-list { display: grid; margin-top: 10px; border-top: 1px solid var(--line); }
.audit-row { display: grid; grid-template-columns: 170px minmax(0,1fr) 150px; gap: 10px; align-items: center; min-height: 34px; border-bottom: 1px solid var(--line); font-size: 9px; }
.audit-row b { color: var(--accent-strong); font: 650 8px/1 ui-monospace, monospace; }
.audit-row span { color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.audit-row time { color: var(--subtle); font: 8px/1 ui-monospace, monospace; text-align: right; }
.audit-empty { margin-top: 10px; color: var(--subtle); font-size: 9px; }
.empty-detail { min-height: 430px; display: flex; align-items: center; justify-content: center; flex-direction: column; text-align: center; }
.empty-mark { width: 42px; height: 42px; display: grid; place-items: center; border: 1px solid #4d4434; border-radius: 50%; color: var(--accent-strong); background: rgba(201,166,95,.04); font-size: 9px; font-weight: 700; }
.empty-detail b { margin-top: 11px; color: var(--text); font-size: 10px; }
.empty-detail span { max-width: 290px; margin-top: 5px; color: var(--subtle); font-size: 9px; line-height: 1.55; }
.group-workspace { display: grid; grid-template-columns: minmax(250px, 300px) minmax(0, 1fr); gap: 10px; align-items: start; }
.group-directory, .group-detail { min-width: 0; }
.group-head-actions { display: flex; align-items: center; gap: 8px; }
.group-delete-control { display: grid; grid-template-columns: minmax(120px, 1fr) auto; align-items: center; gap: 7px; }
.group-create-form { display: grid; gap: 7px; margin-top: 10px; }
.group-create-form .input { width: 100%; height: 30px; padding: 0 8px; font-size: 9px; }
.group-list { display: grid; gap: 3px; max-height: 300px; overflow: auto; margin-top: 10px; }
.group-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; width: 100%; padding: 8px; border: 1px solid transparent; background: transparent; color: var(--text); border-radius: 3px; text-align: left; cursor: pointer; }
.group-row:hover { background: #1a1d20; }
.group-row.active { border-color: #5f523a; background: rgba(201,166,95,.065); }
.group-row span { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.group-row b { color: var(--text); font-size: 10px; font-weight: 600; }
.group-row small { overflow: hidden; color: var(--subtle); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.group-row em { flex: 0 0 auto; color: var(--accent-strong); font-size: 8px; font-style: normal; }
.group-empty, .group-error { display: block; margin-top: 10px; color: var(--subtle); font-size: 9px; }
.group-error { color: #e47d79; }
.group-detail { display: flex; flex-direction: column; gap: 12px; }
.group-controls { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.group-card { min-width: 0; padding: 11px; border: 1px solid var(--line); border-radius: var(--radius-sm); background: var(--panel); }
.member-checks { display: grid; gap: 5px; max-height: 170px; overflow: auto; margin: 10px 0; }
.member-check { display: flex; align-items: center; gap: 6px; color: var(--muted); font-size: 8px; }
.member-check input { accent-color: var(--accent); }
@media (max-width: 1050px) { .group-controls { grid-template-columns: 1fr 1fr; } .group-card:last-child { grid-column: 1 / -1; } }
@media (max-width: 720px) { .group-workspace { grid-template-columns: 1fr; } .group-controls { grid-template-columns: 1fr; } .group-card:last-child { grid-column: auto; } }
@media (max-width: 950px) { .admin-layout { grid-template-columns: 220px minmax(0, 1fr); } .control-grid { grid-template-columns: 1fr; } .account-meta { grid-template-columns: 1fr 1fr; } .role-control { grid-template-columns: auto minmax(110px, 1fr); } .role-control .input { grid-column: 1 / -1; } }
@media (max-width: 720px) { .admin-head { align-items: flex-start; flex-direction: column; } .admin-layout { grid-template-columns: 1fr; } .directory-panel { max-height: 260px; } .user-list { max-height: 195px; } .account-actions { align-items: flex-end; flex-direction: column; } .account-mutation-row { grid-template-columns: 1fr; } .account-mutation-row small { grid-column: auto; } .role-control { grid-template-columns: 1fr; } .role-control .input { grid-column: auto; } .group-delete-control { grid-template-columns: 1fr; } }
@media (max-width: 500px) { .admin-stats { width: 100%; justify-content: space-between; } .detail-head { align-items: flex-start; flex-direction: column; } .account-actions { align-items: flex-start; } .form-grid, .quota-usage { grid-template-columns: 1fr; } }
</style>
