/**
 * 浏览器端的页面级 SEO 元信息。
 *
 * 服务端已经为爬虫输出完整 HTML；这里负责普通浏览器在客户端路由切换、以及歌曲/歌单
 * 异步数据到达后，保持标题、canonical、社交分享信息与结构化数据一致。
 */

export const SITE_ORIGIN = 'https://music.nekocore.cn'
export const DEFAULT_OG_IMAGE = `${SITE_ORIGIN}/og-image.jpg`
export const DEFAULT_ROBOTS =
  'index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1'
export const PRIVATE_ROBOTS = 'noindex, nofollow'
export const NOT_FOUND_ROBOTS = 'noindex, follow'
const SITE_NAME = 'Neko歌姬计划 (Neko Music)'

export const DEFAULT_DESCRIPTION =
  'Neko歌姬计划 - 完全免费的在线音乐播放平台，支持落雪音乐 LX Music LXMC 歌单导入、海量音乐搜索、高品质播放与个性化收藏。无需付费，永久免费。'
export const DEFAULT_KEYWORDS =
  'Neko歌姬计划,免费音乐,在线音乐,音乐播放,音乐搜索,音乐收藏,免费听歌,高品质音乐,无广告音乐,永久免费,落雪音乐,LX Music,LxMusic,LXMC,lxmc,落雪歌单导入'

function upsertMeta(attr, key, content) {
  if (content === undefined || content === null || content === '') return
  let el = document.head.querySelector(`meta[${attr}="${key}"]`)
  if (!el) {
    el = document.createElement('meta')
    el.setAttribute(attr, key)
    document.head.appendChild(el)
  }
  el.setAttribute('content', content)
}

function removeMeta(attr, key) {
  document.head.querySelector(`meta[${attr}="${key}"]`)?.remove()
}

function upsertLink(rel, href) {
  let el = document.head.querySelector(`link[rel="${rel}"]`)
  if (!el) {
    el = document.createElement('link')
    el.setAttribute('rel', rel)
    document.head.appendChild(el)
  }
  el.setAttribute('href', href)
}

function upsertAlternate(hreflang, href) {
  let el = document.head.querySelector(`link[rel="alternate"][hreflang="${hreflang}"]`)
  if (!el) {
    el = document.createElement('link')
    el.setAttribute('rel', 'alternate')
    el.setAttribute('hreflang', hreflang)
    document.head.appendChild(el)
  }
  el.setAttribute('href', href)
}

function upsertJsonLd(id, data) {
  const existing = document.getElementById(id)
  if (!data) {
    existing?.remove()
    return
  }
  const el = existing || document.createElement('script')
  if (!existing) {
    el.type = 'application/ld+json'
    el.id = id
    document.head.appendChild(el)
  }
  el.textContent = JSON.stringify(data)
}

export function absoluteSiteUrl(path) {
  if (!path) return `${SITE_ORIGIN}/`
  try {
    return new URL(path, `${SITE_ORIGIN}/`).toString()
  } catch {
    return `${SITE_ORIGIN}/`
  }
}

export function applyPageSeo({
  title,
  description = DEFAULT_DESCRIPTION,
  keywords = DEFAULT_KEYWORDS,
  path = '/',
  image = DEFAULT_OG_IMAGE,
  imageAlt = title,
  type = 'website',
  robots = DEFAULT_ROBOTS,
  jsonLd = null,
  jsonLdId = 'route-jsonld',
}) {
  const url = absoluteSiteUrl(path)
  const imageUrl = absoluteSiteUrl(image)
  const isDefaultImage = imageUrl === DEFAULT_OG_IMAGE

  document.title = title
  upsertMeta('name', 'application-name', 'NekoMusic')
  upsertMeta('name', 'description', description)
  upsertMeta('name', 'keywords', keywords)
  upsertMeta('name', 'robots', robots)
  upsertMeta('name', 'theme-color', '#04090b')
  upsertMeta('name', 'color-scheme', 'dark')
  upsertMeta('name', 'format-detection', 'telephone=no')
  upsertLink('canonical', url)
  upsertLink('manifest', '/manifest.webmanifest')
  upsertAlternate('zh-CN', url)
  upsertAlternate('en', url)
  upsertAlternate('x-default', url)

  upsertMeta('property', 'og:type', type)
  upsertMeta('property', 'og:site_name', SITE_NAME)
  upsertMeta('property', 'og:url', url)
  upsertMeta('property', 'og:title', title)
  upsertMeta('property', 'og:description', description)
  upsertMeta('property', 'og:image', imageUrl)
  upsertMeta('property', 'og:image:alt', imageAlt)
  upsertMeta('property', 'og:locale', 'zh_CN')
  upsertMeta('property', 'og:locale:alternate', 'en_US')
  if (isDefaultImage) {
    upsertMeta('property', 'og:image:type', 'image/jpeg')
    upsertMeta('property', 'og:image:width', '1024')
    upsertMeta('property', 'og:image:height', '559')
  } else {
    removeMeta('property', 'og:image:type')
    removeMeta('property', 'og:image:width')
    removeMeta('property', 'og:image:height')
  }

  upsertMeta('name', 'twitter:card', 'summary_large_image')
  upsertMeta('name', 'twitter:url', url)
  upsertMeta('name', 'twitter:title', title)
  upsertMeta('name', 'twitter:description', description)
  upsertMeta('name', 'twitter:image', imageUrl)
  upsertMeta('name', 'twitter:image:alt', imageAlt)

  upsertJsonLd(jsonLdId, jsonLd)
}
