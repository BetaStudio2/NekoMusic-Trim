import { createRouter, createWebHistory } from 'vue-router'
import { watch } from 'vue'
import { openAuthDialog } from '@/composables/useAuthDialog'
import { t, locale } from '@/i18n'
import {
  applyPageSeo,
  DEFAULT_DESCRIPTION,
  DEFAULT_KEYWORDS,
  DEFAULT_ROBOTS,
  NOT_FOUND_ROBOTS,
  PRIVATE_ROBOTS,
  SITE_ORIGIN,
} from '@/utils/seoMeta'

// 检查管理员是否已登录
function isAdminLoggedIn() {
  return localStorage.getItem('isAdminLoggedIn') === 'true';
}

// 管理员路由守卫（router v5：用返回值代替已弃用的 next 回调）
const adminGuard = (to) => {
  if (!to.path.startsWith('/admin') || to.path === '/admin/login') return true

  if (!isAdminLoggedIn()) return '/admin/login'

  // 检查用户权限
  const stored = localStorage.getItem('adminInfo')
  if (stored) {
    try {
      const role = JSON.parse(stored).role || 'admin'
      // 审核员不能访问音乐/歌词/VIP 价目/客户端更新页面
      if (role === 'auditor') {
        if (to.path.startsWith('/admin/music')
          || (to.path.startsWith('/admin/lyrics') && !to.path.startsWith('/admin/lyrics-reports'))) {
          return '/admin'
        }
        if (to.path.startsWith('/admin/vip-pricing')) return '/admin'
        if (to.path.startsWith('/admin/releases')) return '/admin'
        if (to.path.startsWith('/admin/settings')) return '/admin'
        // 操作日志仅管理员及以上；审核员可看「安全」页里的访问日志（安全分析需要）
        if (to.path.startsWith('/admin/logs')) return '/admin'
      }
    } catch {
      /* adminInfo 损坏时按已登录处理，交由页面自行兜底 */
    }
  }

  return true
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: {
        title: '首页 - Neko歌姬计划 | 完全免费的音乐平台',
        description:
          'Neko歌姬计划 - 完全免费的在线音乐播放平台；支持落雪音乐 LX Music 导出的 LXMC 文件歌单导入，也可在 Android / PC 客户端从网易云、QQ 音乐、酷狗迁入歌单。开源、搜索与播放免费使用。',
        keywords:
          'Neko歌姬计划,免费音乐,在线音乐,音乐播放,免费听歌,永久免费,无广告音乐,落雪音乐,LX Music,LxMusic,LXMC,lxmc,落雪歌单导入,落雪音乐歌单迁移,网易云歌单迁移,QQ音乐歌单迁移,酷狗歌单迁移,开源音乐'
      }
    },
    {
      path: '/lxmusic',
      alias: ['/lxmc', '/luoxue-music'],
      name: 'lxmusic',
      component: () => import('@/views/LxmusicImportView.vue'),
      meta: {
        canonicalPath: '/lxmusic',
        title: '落雪音乐 LX Music 歌单导入 - LXMC 文件迁移 | Neko歌姬计划',
        description:
          '在线导入落雪音乐（LX Music）导出的 LXMC / .lxmc 歌单文件。上传后自动解析文件内歌单，选择目标歌单，由 Neko 服务器匹配曲库并后台完成落雪歌单迁移。免费、无需安装客户端。',
        keywords:
          '落雪音乐,落雪音乐歌单导入,落雪歌单迁移,LX Music,LxMusic,LXMC,lxmc,lxmc文件,落雪音乐LXMC,落雪音乐怎么导入歌单,LX Music歌单导入,歌单迁移,Neko歌姬计划'
      }
    },
    {
      path: '/tools-lyr',
      name: 'tools-lyr',
      component: () => import('@/views/ToolsLyrView.vue'),
      meta: {
        title: '逐字歌词制作工具 - LRC/NEKO 在线打轴与下载 | Neko歌姬计划',
        description: '在浏览器本地选择音乐、粘贴或输入歌词，通过逐字打轴制作并导出 neko-v1 格式的 .neko 逐字歌词文件。音频与歌词不会上传服务器。',
        keywords: '逐字歌词,歌词制作,歌词打轴,LRC转逐字,LRC转NEKO,neko-v1,.neko,歌词时间轴,在线歌词工具,AMLL歌词',
        jsonLd: [
          {
            '@type': 'WebApplication',
            '@id': `${SITE_ORIGIN}/tools-lyr#webapp`,
            name: '逐字歌词制作工具',
            url: `${SITE_ORIGIN}/tools-lyr`,
            description: '在浏览器本地为 LRC、纯文本或 neko-v1 歌词制作逐字时间轴，并下载 .neko 文件。',
            applicationCategory: 'MultimediaApplication',
            operatingSystem: 'Any',
            browserRequirements: 'Requires JavaScript',
            isAccessibleForFree: true,
            isPartOf: { '@id': `${SITE_ORIGIN}/#website` },
            provider: { '@id': `${SITE_ORIGIN}/#organization` },
            offers: {
              '@type': 'Offer',
              price: '0',
              priceCurrency: 'CNY',
              description: 'Free / 免费',
            },
            featureList: [
              '本地音乐逐字打轴',
              'LRC、纯文本与 neko-v1 导入',
              'AMLL 歌词效果预览',
              '.neko 歌词文件下载',
            ],
          },
          {
            '@type': 'HowTo',
            '@id': `${SITE_ORIGIN}/tools-lyr#howto`,
            name: '在浏览器中制作逐字歌词并下载 .neko 文件',
            description: '使用 Neko歌姬计划逐字歌词制作工具完成本地选曲、歌词导入、逐字打轴、预览与下载。',
            step: [
              { '@type': 'HowToStep', position: 1, name: '选择本地音乐', text: '选择浏览器可播放的音频文件，音频不会上传服务器。' },
              { '@type': 'HowToStep', position: 2, name: '粘贴或导入歌词', text: '粘贴纯文本、LRC、neko-v1 JSON，或导入 .lrc / .neko 文件。' },
              { '@type': 'HowToStep', position: 3, name: '逐字打轴', text: '播放音乐，在目标文字出现时点击记录；空格和标点不会成为目标。' },
              { '@type': 'HowToStep', position: 4, name: '预览效果', text: '切换到效果预览，检查逐字高亮、自动跟随和行间衔接。' },
              { '@type': 'HowToStep', position: 5, name: '下载歌词', text: '确认时间轴完成后下载 neko-v1 格式的 .neko 歌词文件。' },
            ],
          },
          {
            '@type': 'FAQPage',
            '@id': `${SITE_ORIGIN}/tools-lyr#faq`,
            mainEntity: [
              {
                '@type': 'Question',
                name: '逐字歌词制作工具是免费的吗？',
                acceptedAnswer: { '@type': 'Answer', text: '是，工具完全免费，无需登录，也不需要安装客户端。' },
              },
              {
                '@type': 'Question',
                name: '音乐会上传到 Neko歌姬计划吗？',
                acceptedAnswer: { '@type': 'Answer', text: '不会。音乐只在浏览器本地播放，歌词和时间轴也不会发送到服务器。' },
              },
              {
                '@type': 'Question',
                name: '可以导入已有的 LRC 或 .neko 吗？',
                acceptedAnswer: { '@type': 'Answer', text: '可以。支持粘贴 LRC、纯文本、neko-v1 JSON，也支持直接导入 .lrc 或 .neko 文件。' },
              },
              {
                '@type': 'Question',
                name: '.neko 文件是什么？',
                acceptedAnswer: { '@type': 'Answer', text: '.neko 是 UTF-8 编码的 neko-v1 JSON 歌词文件，包含行级、逐字/逐词时间轴、翻译和罗马音等字段。' },
              },
            ],
          },
        ],
      }
    },
    {
      path: '/about',
      name: 'about',
      component: () => import('@/views/AboutView.vue'),
      meta: {
        title: '关于我们 - Neko歌姬计划 | 免费音乐平台',
        description: '了解Neko歌姬计划项目，一个完全免费的在线音乐播放平台。无需付费，永久免费，享受高品质音乐体验。',
        keywords: '关于我们,Neko歌姬计划介绍,免费音乐平台,音乐项目'
      }
    },
    {
      path: '/privacy',
      name: 'privacy',
      component: () => import('@/views/PrivacyPolicyView.vue'),
      meta: {
        title: '隐私政策 - Neko歌姬计划',
        description: '了解 Neko歌姬计划如何收集、使用、保存和保护用户信息。',
        keywords: '隐私政策,个人信息保护,Neko歌姬计划'
      }
    },
    {
      path: '/download',
      name: 'download',
      component: () => import('@/views/DownloadView.vue'),
      meta: {
        title: '下载客户端 - Neko歌姬计划 | 免费音乐应用',
        description:
          '下载 Neko 云音乐 Android / PC 客户端；Web 支持导入落雪音乐 LX Music 的 LXMC / .lxmc 歌单，客户端支持从网易云音乐、QQ 音乐、酷狗音乐迁入歌单。完全免费、开源透明，多端一致体验。',
        keywords: 'Neko歌姬计划下载,APP下载,免费音乐APP,移动音乐,PC下载,桌面音乐,落雪音乐,LX Music,LXMC,lxmc,落雪歌单导入,网易云导入歌单,QQ音乐导入歌单,酷狗音乐导入歌单'
      }
    },

    {
      path: '/upload',
      name: 'upload',
      component: () => import('@/views/UploadMusicView.vue'),
      meta: {
        title: '上传音乐 - Neko歌姬计划 | 免费音乐上传',
        description: '上传您的音乐到Neko歌姬计划平台，分享您的作品给更多人。完全免费，无需付费。',
        keywords: '音乐上传,上传歌曲,分享音乐,免费上传'
      }
    },
    {
      // 查询词走查询串：放进路径时 encodeURIComponent 会把 / 编成 %2F，
      // Jetty 会以「Ambiguous URI path separator」直接 400，请求根本到不了前端。
      path: '/search',
      name: 'search',
      component: () => import('@/views/SearchResultsView.vue'),
      meta: {
        title: '搜索音乐 - Neko歌姬计划 | 免费音乐搜索',
        description: '在Neko歌姬计划免费搜索您喜爱的音乐，发现更多精彩免费音乐内容。完全免费，无需付费。',
        keywords: '音乐搜索,免费音乐搜索,在线搜索,免费听歌'
      }
    },
    {
      // 兼容历史分享链接 /search/{query}，统一转到查询串形式
      path: '/search/:query',
      redirect: (to) => ({ path: '/search', query: { q: to.params.query } })
    },
    {
      // 登录已改为全局弹窗：旧链接/书签仍可用，落到首页并弹出登录
      path: '/login',
      name: 'login',
      redirect: () => {
        openAuthDialog('login')
        return { path: '/' }
      }
    },
    {
      path: '/register',
      name: 'register',
      redirect: () => {
        openAuthDialog('register')
        return { path: '/' }
      }
    },
    {
      path: '/forgot-password',
      name: 'forgot-password',
      component: () => import('@/views/ForgotPasswordView.vue'),
      meta: {
        title: '忘记密码 - Neko歌姬计划 | 免费音乐平台',
        description: '忘记密码？前往 NekoAuth 统一账号中心重置您的梦幻网络通行证密码。',
        keywords: '忘记密码,重置密码,找回密码'
      }
    },
    {
      path: '/sso/complete',
      name: 'oauth-complete',
      component: () => import('@/views/AuthSsoCompleteView.vue'),
      meta: {
        title: '正在完成登录 - Neko歌姬计划',
        description: '正在完成 NekoAuth 统一登录。',
        keywords: '登录,NekoAuth,单点登录'
      }
    },
    {
      path: '/sso/native-complete',
      name: 'oauth-native-complete',
      component: () => import('@/views/AuthNativeSsoCompleteView.vue'),
      meta: {
        title: '正在返回客户端 - Neko歌姬计划',
        description: '正在完成 NekoAuth 统一登录。',
        keywords: '登录,NekoAuth,单点登录'
      }
    },
    {
      // 旧回调地址兼容；服务端只会在新流程中跳转到 /sso/complete。
      path: '/oauth/nekocore/complete',
      redirect: (to) => ({ path: '/sso/complete', query: to.query }),
    },
    {
      path: '/favorites',
      name: 'favorites',
      component: () => import('@/views/UserFavoritesView.vue'),
      meta: {
        title: '我的收藏 - Neko歌姬计划 | 免费音乐收藏',
        description: '查看您在Neko歌姬计划收藏的免费音乐，管理您的个人音乐收藏夹。完全免费，无需付费。',
        keywords: '音乐收藏,我的收藏,免费收藏,个人音乐'
      }
    },
    {
      path: '/playlists',
      name: 'playlists',
      component: () => import('@/views/UserPlaylistsView.vue'),
      meta: {
        title: '我的歌单 - Neko歌姬计划 | 免费音乐歌单',
        description: '查看和管理您的歌单，创建个性化音乐播放列表。完全免费，无需付费。',
        keywords: '歌单,我的歌单,音乐歌单,播放列表'
      }
    },
    {
      path: '/notifications',
      name: 'notifications',
      component: () => import('@/views/NotificationsView.vue'),
      meta: {
        title: '消息中心 - Neko歌姬计划',
        description: '查看评论回复等站内消息，离线期间的消息上线后自动补齐。',
        keywords: '消息中心,站内消息,评论回复,通知'
      }
    },
    {
      path: '/account',
      name: 'account',
      component: () => import('@/views/UserProfileView.vue'),
      meta: {
        title: '个人中心 - Neko歌姬计划',
        description: '查看账户信息与会员状态，管理密码与安全设置。',
        keywords: '个人中心,账户,会员'
      }
    },
    {
      path: '/vip',
      name: 'vip',
      component: () => import('@/views/UserVipView.vue'),
      meta: {
        title: '会员中心 - Neko歌姬计划',
        description: '查看会员状态与到期时间。',
        keywords: '会员,VIP,会员中心'
      }
    },
    {
      path: '/ranking',
      name: 'ranking',
      component: () => import('@/views/RankingView.vue'),
      meta: {
        title: '热门音乐排行榜 - Neko歌姬计划 | 免费音乐排行',
        description: '查看基于播放次数排序的热门音乐排行榜，发现最受欢迎的免费音乐。完全免费，无需付费。',
        keywords: '热门音乐,音乐排行榜,免费音乐排行,热门排行'
      }
    },
    {
      path: '/latest',
      name: 'latest',
      component: () => import('@/views/LatestView.vue'),
      meta: {
        title: '最新音乐 - Neko歌姬计划 | 免费新歌',
        description: '查看刚刚上传的最新音乐，发现最新的免费音乐资源。完全免费，无需付费。',
        keywords: '最新音乐,新歌上线,免费新歌,音乐上新'
      }
    },
    {
      path: '/playlist/create',
      name: 'create-playlist',
      component: () => import('@/views/CreatePlaylistView.vue'),
      meta: {
        title: '创建歌单 - Neko歌姬计划 | 免费音乐歌单',
        description: '创建新的歌单，整理您喜爱的免费音乐。完全免费，无需付费。',
        keywords: '创建歌单,新建歌单,音乐歌单'
      }
    },
    {
      path: '/playlist/:id',
      name: 'playlist-detail',
      component: () => import('@/views/PlaylistDetailView.vue'),
      props: true,
      meta: {
        title: '歌单详情 - Neko歌姬计划 | 免费音乐歌单',
        description: '查看歌单详情，播放歌单中的免费音乐。完全免费，无需付费。',
        keywords: '歌单详情,歌单播放,音乐歌单'
      }
    },
    {
      path: '/admin/login',
      name: 'admin-login',
      component: () => import('@/views/admin/AdminLoginView.vue'),
      meta: {
        title: '管理员登录 - Neko歌姬计划',
        description: '管理员登录页面，管理免费音乐平台内容。',
        keywords: '管理员登录,后台管理'
      }
    },
    {
      path: '/admin',
      component: () => import('@/layouts/AdminLayout.vue'),
      children: [
        {
          path: '',
          name: 'admin',
          component: () => import('@/views/admin/AdminView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '统计分析 - Neko歌姬计划',
            description: '管理后台首页：平台业务数据统计与趋势。',
            keywords: '统计分析,数据统计,管理后台'
          }
        },
        {
          path: 'security',
          name: 'admin-security',
          component: () => import('@/views/admin/AdminSecurityView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '安全 - Neko歌姬计划',
            description: '访问日志与安全分析：访问量、来源与威胁判定。',
            keywords: '安全,访问日志,访问量,安全分析,管理后台'
          }
        },
        {
          path: 'music',
          name: 'admin-music',
          component: () => import('@/views/admin/AdminMusicView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '音乐管理 - Neko歌姬计划',
            description: '管理平台免费音乐资源，上传、编辑、删除免费音乐。',
            keywords: '音乐管理,音乐上传,免费音乐管理'
          }
        },
        {
          path: 'lyrics',
          name: 'admin-lyrics',
          component: () => import('@/views/admin/AdminLyricsEditorView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '歌词编辑 - Neko歌姬计划',
            description: '以文件管理器方式在线编辑平台歌词文件。',
            keywords: '歌词编辑,在线编辑,LRC,后台管理'
          }
        },
        {
          path: 'lyrics-reports',
          name: 'admin-lyrics-reports',
          component: () => import('@/views/admin/AdminLyricsReportsView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '歌词报错审核 - Neko歌姬计划',
            description: '审核用户提交的歌词修改、翻译和逐字时间轴报错。',
            keywords: '歌词报错,歌词审核,后台管理'
          }
        },
        {
          path: 'audit',
          name: 'admin-audit',
          component: () => import('@/views/admin/AdminAuditView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '审核管理 - Neko歌姬计划',
            description: '审核用户上传的音乐，管理待审核的免费音乐内容。',
            keywords: '审核管理,音乐审核,待审核,免费音乐审核'
          }
        },
        {
          path: 'vip-pricing',
          name: 'admin-vip-pricing',
          component: () => import('@/views/admin/AdminVipPricingView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: 'VIP 价目 - Neko歌姬计划',
            description: '管理 VIP 套餐价目表。',
            keywords: 'VIP,价目,管理'
          }
        },
        {
          path: 'releases',
          name: 'admin-releases',
          component: () => import('@/views/admin/AdminReleasesView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '客户端更新 - Neko歌姬计划',
            description: '管理客户端版本号与安装包上传。',
            keywords: '客户端,更新,安装包,管理'
          }
        },
        {
          path: 'settings',
          name: 'admin-settings',
          component: () => import('@/views/admin/AdminSettingsView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '系统设置 - Neko歌姬计划',
            description: '维护平台运行时配置。',
            keywords: '系统设置,配置,管理'
          }
        },
        {
          path: 'logs',
          name: 'admin-logs',
          component: () => import('@/views/admin/AdminLogsView.vue'),
          beforeEnter: adminGuard,
          meta: {
            title: '操作日志 - Neko歌姬计划',
            description: '查看后台写操作日志（系统设置修改、审核、音乐上传修改删除、歌词修改等）。',
            keywords: '操作日志,后台操作,审核日志,音乐上传,歌词修改,管理'
          }
        },
      ]
    },
    {
      path: '/detail/:id',
      name: 'detail',
      component: () => import('@/views/PlayerView.vue'),
      // 注意：不要开 props: true —— 播放页整屏 Teleport 到 body，根是片段，
      // 无法继承非 prop 属性，多传的 id 只会触发 Vue 的 "Extraneous
      // non-props attributes" 警告。页面自己读 route.params.id。
      meta: {
        title: '音乐详情 - Neko歌姬计划 | 免费音乐播放',
        description: '查看免费音乐详细信息，免费播放高品质音乐。Neko歌姬计划提供完全免费的音乐播放服务。',
        keywords: '音乐详情,音乐播放,免费音乐播放,免费听歌'
      }
    },
    {
      path: '/lyrics-report/:id',
      name: 'lyrics-report',
      component: () => import('@/views/LyricsReportView.vue'),
      meta: {
        title: '歌词报错 - Neko歌姬计划',
        description: '协助修改歌词、添加翻译并完善逐字时间轴，提交后由管理员审核。',
        keywords: '歌词报错,歌词修改,翻译,逐字歌词'
      }
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/ErrorView.vue'),
      meta: {
        title: '404 - 页面未找到 - Neko歌姬计划',
        description: '您访问的页面不存在或已被移除。返回首页继续探索免费音乐。',
        keywords: '404,页面未找到,错误页面'
      }
    }
  ]
})

// 全局路由守卫 - 更新标题 / canonical / OG / robots / JSON-LD
// （router v5：用返回值代替已弃用的 next 回调）
//
// 这里【不再】把移动设备硬重定向到 /download。原先手机访问首页 / 搜索 /
// 收藏等会被直接踢走，整站手机端不可用。App 导流改由 MobileAppBanner
// 软引导横幅承担（见 src/components/MobileAppBanner.vue），
// 真正的「拉起 App」由 src/utils/nativeAppOpen.js 负责。
/** 账号私有页与后台不参与收录，避免登录页 / 空列表页被索引进结果 */
const NOINDEX_PREFIXES = [
  '/admin', '/favorites', '/playlists', '/account', '/vip', '/upload',
  '/lyrics-report',
  '/notifications', '/playlist/create', '/forgot-password', '/login', '/register',
  '/oauth/nekocore', '/sso',
]

/**
 * 路由名 → 文案键。
 *
 * 路由的 `meta.title` 仍保留中文：它是给搜索引擎看的（description / keywords 同理），
 * 只有浏览器标签页与 og:title 走这里的多语言文案。
 */
const ROUTE_TITLE_KEYS = {
  home: 'route.home',
  lxmusic: 'route.lxmusic',
  'tools-lyr': 'route.toolsLyr',
  about: 'route.about',
  privacy: 'route.privacy',
  download: 'route.download',
  upload: 'route.upload',
  search: 'route.search',
  'forgot-password': 'route.forgotPassword',
  favorites: 'route.favorites',
  playlists: 'route.playlists',
  notifications: 'route.notifications',
  account: 'route.account',
  vip: 'route.vip',
  ranking: 'route.ranking',
  latest: 'route.latest',
  'create-playlist': 'route.createPlaylist',
  'playlist-detail': 'route.playlistDetail',
  'admin-login': 'route.adminLogin',
  admin: 'route.adminStats',
  'admin-security': 'route.adminSecurity',
  'admin-music': 'route.adminMusic',
  'admin-lyrics': 'route.adminLyrics',
  'admin-lyrics-reports': 'route.adminLyricsReports',
  'admin-audit': 'route.adminAudit',
  'admin-vip-pricing': 'route.adminVipPricing',
  'admin-releases': 'route.adminReleases',
  'admin-settings': 'route.adminSettings',
  'admin-logs': 'route.adminLogs',
  detail: 'route.detail',
  'lyrics-report': 'route.lyricsReport',
  'not-found': 'route.notFound',
}

/** 当前路由对应的标签页标题；没有译文键时退回路由自带的中文标题 */
function routeTitle(to) {
  const key = ROUTE_TITLE_KEYS[to.name]
  if (!key) return to.meta.title || 'Neko歌姬计划 - 完全免费的在线音乐播放平台'
  const translated = t(key)
  // t() 查不到会原样返回键名，那种情况按「没翻译」处理
  return translated === key ? to.meta.title || translated : translated
}

router.beforeEach((to) => {
  const title = routeTitle(to)
  const description = to.meta.description || DEFAULT_DESCRIPTION
  const keywords = to.meta.keywords || DEFAULT_KEYWORDS

  const isNotFound = to.name === 'not-found'
  const isPrivate = NOINDEX_PREFIXES.some((p) => to.path === p || to.path.startsWith(`${p}/`))
  const canonicalPath = to.meta.canonicalPath || to.path
  const url = `${SITE_ORIGIN}${canonicalPath === '/' ? '/' : canonicalPath}`
  applyPageSeo({
    title,
    description,
    keywords,
    path: canonicalPath,
    robots: isNotFound ? NOT_FOUND_ROBOTS : isPrivate ? PRIVATE_ROBOTS : DEFAULT_ROBOTS,
    // 每条路由注入 WebPage 实体；页面可通过 meta.jsonLd 追加 WebApplication / HowTo / FAQ 等实体。
    jsonLd: {
      '@context': 'https://schema.org',
      '@graph': [
        {
          '@type': 'WebPage',
          '@id': `${url}#webpage`,
          url,
          name: title,
          description,
          isPartOf: { '@id': `${SITE_ORIGIN}/#website` },
          inLanguage: ['zh-CN', 'en'],
          ...(to.name === 'tools-lyr' ? { mainEntity: { '@id': `${url}#webapp` } } : {}),
        },
        ...(Array.isArray(to.meta.jsonLd)
          ? to.meta.jsonLd
          : to.meta.jsonLd ? [to.meta.jsonLd] : []),
      ],
    },
  })

  return true
})

// 切语言后标题不会自己变（它不参与模板渲染），这里补一次
watch(locale, () => {
  const current = router.currentRoute.value
  if (current?.name) document.title = routeTitle(current)
})

export default router
