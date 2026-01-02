<template>
  <el-container class="app-container">
    <!-- 左侧菜单 -->
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <span>🚀 Manjaro迁移</span>
      </div>
      
      <div class="spacer"></div>
      
      <!-- 登录状态 -->
      <div class="user-section" v-if="isLoggedIn">
        <div class="user-avatar">M</div>
        <div class="user-info">
          <div class="name">Manjaro Supply</div>
          <div class="role">已登录</div>
        </div>
      </div>
      <div class="user-section login-btn" v-else @click="showLogin = true">
        <div class="user-avatar guest">?</div>
        <div class="user-info">
          <div class="name">未登录</div>
          <div class="role">点击登录</div>
        </div>
      </div>
    </el-aside>
    
    <!-- 主内容 -->
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <span class="header-title">Lazada → Manjaro 商品迁移</span>
          <el-tag v-if="isLoggedIn" type="success">已连接</el-tag>
          <el-tag v-else type="danger">未登录</el-tag>
        </div>
        <div class="header-right">
          <el-tag type="info">分类: {{ categoryCount }}</el-tag>
        </div>
      </el-header>
      
      <el-main class="main-content">
        <!-- 统计卡片 -->
        <el-row :gutter="20" class="stat-row">
          <el-col :span="6">
            <div class="stat-card warning">
              <div class="label">待处理</div>
              <div class="value">{{ stats.pending }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card primary">
              <div class="label">处理中</div>
              <div class="value">{{ stats.processing }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card success">
              <div class="label">已完成</div>
              <div class="value">{{ stats.success }}</div>
            </div>
          </el-col>
          <el-col :span="6">
            <div class="stat-card danger">
              <div class="label">失败/跳过</div>
              <div class="value">{{ stats.failed + stats.skipped }}</div>
            </div>
          </el-col>
        </el-row>
        
        <!-- 主面板 -->
        <el-row :gutter="20" class="panel-row">
          <el-col :span="16">
            <!-- 商品列表 -->
            <el-card class="product-card">
              <template #header>
                <div class="card-header">
                  <span>商品列表 ({{ products.length }})</span>
                  <el-button size="small" @click="refreshProducts">刷新</el-button>
                </div>
              </template>
              <div v-if="!products.length" class="empty-box">
                <el-empty description="暂无商品，上传Excel开始任务" />
              </div>
              <el-table v-else :data="products" size="small" max-height="280">
                <el-table-column prop="id" label="ID" width="60" />
                <el-table-column prop="title" label="商品标题" show-overflow-tooltip />
                <el-table-column prop="price" label="价格" width="90" />
                <el-table-column label="状态" width="90">
                  <template #default="{row}">
                    <el-tag :type="getStatusType(row.status)" size="small">{{ getStatusText(row.status) }}</el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-card>
            
            <!-- 日志 -->
            <el-card class="log-card">
              <template #header><span style="color:#fff">运行日志</span></template>
              <div class="log-content" ref="logArea">
                <div v-for="(l, i) in logs" :key="i" class="log-line">
                  <span class="log-time">[{{ l.time }}]</span>
                  <span :class="'log-' + l.level">{{ l.message }}</span>
                </div>
              </div>
            </el-card>
          </el-col>
          
          <el-col :span="8">
            <!-- 分类同步 -->
            <el-card class="config-card">
              <template #header>分类管理</template>
              <div class="category-row">
                <el-button type="primary" @click="syncCategories" :loading="syncingCategories">
                  同步分类
                </el-button>
                <el-tag v-if="categoryCount" type="success">{{ categoryCount }} 个</el-tag>
              </div>
              <div style="margin-top: 16px; padding-top: 16px; border-top: 1px solid #eee;">
                <el-popconfirm title="确定清空所有数据？此操作不可恢复！" @confirm="clearAllData">
                  <template #reference>
                    <el-button type="danger" plain size="small">清空全部数据</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </el-card>
            
            <!-- Excel上传 -->
            <el-card class="config-card">
              <template #header>文档上传</template>
              <el-form label-position="top" size="small">
                <el-form-item label="Excel文件">
                  <el-upload 
                    ref="uploadRef"
                    action="#" 
                    :auto-upload="false" 
                    :show-file-list="true"
                    :limit="1"
                    :on-change="onExcelChange"
                    :on-remove="onExcelRemove"
                    accept=".xlsx,.xls"
                  >
                    <el-button>选择文件</el-button>
                  </el-upload>
                </el-form-item>
                <el-form-item label="链接列 (从1开始)">
                  <el-input-number v-model="linkColumn" :min="1" :max="50" />
                </el-form-item>
                <el-form-item label="起始行 (从1开始)">
                  <el-input-number v-model="startRow" :min="1" :max="1000" />
                </el-form-item>
              </el-form>
              <div class="action-buttons">
                <el-button type="primary" @click="startTask" :disabled="!canStart || isRunning" :loading="isRunning">
                  {{ isRunning ? '运行中...' : '开始任务' }}
                </el-button>
                <el-button type="danger" plain @click="stopTask" :disabled="!isRunning">停止</el-button>
              </div>
            </el-card>
            
            <!-- 进度 -->
            <el-card class="config-card" v-if="isRunning || taskTotal > 0">
              <template #header>任务进度</template>
              <el-progress :percentage="progressPercent" :status="progressStatus" />
              <div class="progress-info">
                <span>{{ taskProcessed }} / {{ taskTotal }}</span>
                <span v-if="currentUrl" class="current-url">{{ currentUrl }}</span>
              </div>
            </el-card>

            <!-- API设置 -->
            <el-card class="config-card">
              <template #header>
                <div class="card-header">
                  <span>API设置</span>
                  <el-button size="small" text @click="showSettings = true">编辑</el-button>
                </div>
              </template>
              <div class="settings-status">
                <div><span>通义千问:</span> <el-tag :type="settings.qwenKey ? 'success' : 'danger'" size="small">{{ settings.qwenKey ? '已配置' : '未配置' }}</el-tag></div>
                <div><span>Oxylabs:</span> <el-tag :type="settings.oxylabsUser ? 'success' : 'danger'" size="small">{{ settings.oxylabsUser ? '已配置' : '未配置' }}</el-tag></div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </el-main>
    </el-container>
    
    <!-- 登录弹窗 -->
    <el-dialog v-model="showLogin" title="Manjaro Supply 登录" width="420px" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="账号">
          <el-input v-model="loginForm.username" placeholder="请输入账号" size="large" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" size="large" show-password />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="captcha-row">
            <el-input v-model="loginForm.captcha" placeholder="验证码" size="large" @keyup.enter="login" />
            <img v-if="captchaImage" :src="captchaImage" @click="refreshCaptcha" class="captcha-img" title="点击刷新">
            <el-button v-else @click="refreshCaptcha" size="large">获取</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showLogin = false">取消</el-button>
        <el-button type="primary" @click="login" :loading="loginLoading">登录</el-button>
      </template>
    </el-dialog>

    <!-- 设置弹窗 -->
    <el-dialog v-model="showSettings" title="API设置" width="500px">
      <el-form label-position="top">
        <el-divider content-position="left">通义千问 (分类匹配)</el-divider>
        <el-form-item label="API Key">
          <el-input v-model="settingsForm.qwenKey" placeholder="sk-xxxxxxxx" show-password />
        </el-form-item>
        <el-divider content-position="left">Oxylabs (爬取Lazada)</el-divider>
        <el-form-item label="用户名">
          <el-input v-model="settingsForm.oxylabsUser" placeholder="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="settingsForm.oxylabsPass" placeholder="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showSettings = false">取消</el-button>
        <el-button type="primary" @click="saveSettings">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

// 登录
const showLogin = ref(false)
const loginForm = ref({ username: '', password: '', captcha: '' })
const captchaImage = ref('')
const loginLoading = ref(false)
const isLoggedIn = ref(false)

// 分类
const categoryCount = ref(0)
const syncingCategories = ref(false)

// 设置
const showSettings = ref(false)
const settings = ref({ qwenKey: '', oxylabsUser: '', oxylabsPass: '' })
const settingsForm = ref({ qwenKey: '', oxylabsUser: '', oxylabsPass: '' })

// Excel
const uploadRef = ref(null)
const excelFile = ref(null)
const linkColumn = ref(8)
const startRow = ref(2)

// 任务
const isRunning = ref(false)
const taskTotal = ref(0)
const taskProcessed = ref(0)
const taskSuccess = ref(0)
const taskFailed = ref(0)
const taskSkipped = ref(0)
const currentUrl = ref('')

// 数据
const products = ref([])
const logs = ref([])
const logArea = ref(null)

const stats = computed(() => ({
  pending: taskTotal.value - taskProcessed.value,
  processing: isRunning.value ? 1 : 0,
  success: taskSuccess.value,
  failed: taskFailed.value,
  skipped: taskSkipped.value
}))

const canStart = computed(() => isLoggedIn.value && excelFile.value)
const progressPercent = computed(() => taskTotal.value ? Math.round(taskProcessed.value / taskTotal.value * 100) : 0)
const progressStatus = computed(() => {
  if (!isRunning.value && taskProcessed.value === taskTotal.value && taskTotal.value > 0) return 'success'
  return ''
})

// 工具
const addLog = (level, msg) => {
  logs.value.push({ time: new Date().toLocaleTimeString(), level, message: msg })
  if (logs.value.length > 200) logs.value.shift()
  nextTick(() => { if (logArea.value) logArea.value.scrollTop = logArea.value.scrollHeight })
}

const getStatusText = s => ({ 0: '待处理', 1: '处理中', 2: '已完成', '-1': '失败' }[s] || '未知')
const getStatusType = s => ({ 0: 'warning', 1: '', 2: 'success', '-1': 'danger' }[s] || 'info')

// API调用
const refreshCaptcha = async () => {
  try {
    const { data } = await api.get('/captcha')
    if (data.success) {
      captchaImage.value = data.captchaBase64
      addLog('info', '验证码已刷新')
    } else {
      addLog('error', '获取验证码失败')
    }
  } catch (e) { addLog('error', '获取验证码失败: ' + e.message) }
}

const login = async () => {
  if (!loginForm.value.username || !loginForm.value.password) {
    ElMessage.warning('请输入账号密码')
    return
  }
  loginLoading.value = true
  try {
    const { data } = await api.post('/login', loginForm.value)
    if (data.success) {
      isLoggedIn.value = true
      showLogin.value = false
      addLog('success', '登录成功')
      ElMessage.success('登录成功')
    } else {
      addLog('error', '登录失败: ' + data.message)
      ElMessage.error(data.message || '登录失败')
      refreshCaptcha()
    }
  } catch (e) {
    addLog('error', '登录请求失败')
    ElMessage.error('登录请求失败')
  }
  loginLoading.value = false
}

const checkLoginStatus = async () => {
  try {
    const { data } = await api.get('/login/status')
    isLoggedIn.value = data.loggedIn
    if (data.loggedIn) addLog('success', '已恢复登录状态')
  } catch (e) {}
}

const syncCategories = async () => {
  syncingCategories.value = true
  addLog('info', '同步分类中...')
  try {
    const { data } = await api.post('/categories/sync')
    if (data.success) {
      categoryCount.value = data.count
      addLog('success', `同步完成: ${data.count} 个分类`)
    } else {
      addLog('error', '同步失败: ' + data.message)
    }
  } catch (e) { addLog('error', '同步失败') }
  syncingCategories.value = false
}

const getCategoryCount = async () => {
  try {
    const { data } = await api.get('/categories/count')
    categoryCount.value = data.count || 0
  } catch (e) {}
}

const onExcelChange = (file) => {
  excelFile.value = file.raw
  addLog('info', '已选择: ' + file.name)
}

const onExcelRemove = () => {
  excelFile.value = null
}

const startTask = async () => {
  if (!excelFile.value) {
    ElMessage.warning('请先选择Excel文件')
    return
  }
  
  const formData = new FormData()
  formData.append('file', excelFile.value)
  formData.append('linkColumn', linkColumn.value)
  formData.append('startRow', startRow.value)
  
  addLog('info', '上传Excel并启动任务...')
  
  try {
    const { data } = await api.post('/task/upload', formData)
    if (data.success) {
      isRunning.value = true
      taskTotal.value = data.total
      taskProcessed.value = 0
      taskSuccess.value = 0
      taskFailed.value = 0
      taskSkipped.value = 0
      addLog('success', `任务已启动，共 ${data.total} 个链接`)
      startPolling()
    } else {
      addLog('error', '启动失败: ' + data.message)
      ElMessage.error(data.message)
    }
  } catch (e) {
    addLog('error', '上传失败: ' + e.message)
    ElMessage.error('上传失败')
  }
}

const stopTask = async () => {
  try {
    await api.post('/task/stop')
    isRunning.value = false
    addLog('warning', '任务已停止')
  } catch (e) { addLog('error', '停止失败') }
}

const refreshProducts = async () => {
  try {
    const { data } = await api.get('/products')
    if (data.success) products.value = data.data || []
  } catch (e) {}
}

// 轮询
let pollTimer = null
const startPolling = () => {
  if (pollTimer) return
  pollTimer = setInterval(async () => {
    try {
      const { data } = await api.get('/task/progress')
      taskProcessed.value = data.processed
      taskSuccess.value = data.success
      taskFailed.value = data.failed
      taskSkipped.value = data.skipped
      currentUrl.value = data.currentUrl || ''
      
      if (!data.running) {
        isRunning.value = false
        stopPolling()
        addLog('success', `任务完成: 成功${data.success}, 失败${data.failed}, 跳过${data.skipped}`)
        ElMessage.success('任务完成')
      }
    } catch (e) {}
    
    await refreshProducts()
  }, 2000)
}

const stopPolling = () => {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

const clearAllData = async () => {
  try {
    const { data } = await api.delete('/data/all')
    if (data.success) {
      products.value = []
      categoryCount.value = 0
      taskTotal.value = 0
      taskProcessed.value = 0
      taskSuccess.value = 0
      taskFailed.value = 0
      taskSkipped.value = 0
      addLog('success', '所有数据已清空')
      ElMessage.success('数据已清空')
    } else {
      addLog('error', '清空失败: ' + data.message)
    }
  } catch (e) { 
    addLog('error', '清空失败') 
  }
}

const loadSettings = async () => {
  try {
    const { data } = await api.get('/settings')
    if (data.success) {
      settings.value = data.data || {}
      settingsForm.value = { ...settings.value }
    }
  } catch (e) {}
}

const saveSettings = async () => {
  try {
    const { data } = await api.post('/settings', settingsForm.value)
    if (data.success) {
      settings.value = { ...settingsForm.value }
      showSettings.value = false
      addLog('success', 'API设置已保存')
      ElMessage.success('设置已保存')
    } else {
      addLog('error', '保存失败: ' + data.message)
    }
  } catch (e) { 
    addLog('error', '保存失败') 
  }
}

onMounted(async () => {
  addLog('info', '系统初始化...')
  await loadSettings()
  await checkLoginStatus()
  await getCategoryCount()
  await refreshProducts()
  addLog('success', '初始化完成')
})

onUnmounted(() => stopPolling())
</script>
