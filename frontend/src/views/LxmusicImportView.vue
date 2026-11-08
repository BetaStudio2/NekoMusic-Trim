<script setup>
import { computed } from 'vue'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NTag } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { t } from '@/i18n'

const features = computed(() => [
  {
    icon: 'file-up',
    title: t('lxmusic.featureFileTitle'),
    desc: t('lxmusic.featureFileDesc'),
  },
  {
    icon: 'search',
    title: t('lxmusic.featureMatchTitle'),
    desc: t('lxmusic.featureMatchDesc'),
  },
  {
    icon: 'list-music',
    title: t('lxmusic.featureOrderTitle'),
    desc: t('lxmusic.featureOrderDesc'),
  },
  {
    icon: 'cloud',
    title: t('lxmusic.featureBackgroundTitle'),
    desc: t('lxmusic.featureBackgroundDesc'),
  },
])

const steps = computed(() => [
  t('lxmusic.step1'),
  t('lxmusic.step2'),
  t('lxmusic.step3'),
  t('lxmusic.step4'),
])

const faqs = computed(() => [
  { q: t('lxmusic.faq1q'), a: t('lxmusic.faq1a') },
  { q: t('lxmusic.faq2q'), a: t('lxmusic.faq2a') },
  { q: t('lxmusic.faq3q'), a: t('lxmusic.faq3a') },
  { q: t('lxmusic.faq4q'), a: t('lxmusic.faq4a') },
])
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="default">
    <section class="lx-hero">
      <div class="lx-hero__copy">
        <NTag variant="accent" icon="sparkles">{{ $t('lxmusic.badge') }}</NTag>
        <h1 class="lx-hero__title">
          <span>{{ $t('lxmusic.titleLead') }}</span>
          <strong>{{ $t('lxmusic.titleStrong') }}</strong>
        </h1>
        <p class="lx-hero__lede">{{ $t('lxmusic.lede') }}</p>
        <div class="lx-hero__actions">
          <NButton variant="primary" icon="file-up" to="/playlists?import=lxmc">
            {{ $t('lxmusic.importNow') }}
          </NButton>
          <NButton variant="secondary" icon="list-music" to="/playlists">
            {{ $t('lxmusic.openPlaylists') }}
          </NButton>
        </div>
        <p class="lx-hero__note">{{ $t('lxmusic.disclaimer') }}</p>
      </div>

      <div class="lx-file" aria-hidden="true">
        <div class="lx-file__glow"></div>
        <div class="lx-file__sheet">
          <span class="lx-file__badge"><NIcon name="file-music" :size="18" /></span>
          <span class="lx-file__name">LX Music</span>
          <strong>.lxmc</strong>
          <span class="lx-file__line"></span>
          <span class="lx-file__line lx-file__line--short"></span>
          <span class="lx-file__check"><NIcon name="check" :size="15" /> {{ $t('lxmusic.fileReady') }}</span>
        </div>
      </div>
    </section>

    <section class="lx-section" :aria-label="$t('lxmusic.featureAria')">
      <div class="lx-section__head">
        <h2>{{ $t('lxmusic.featureTitle') }}</h2>
        <p>{{ $t('lxmusic.featureSub') }}</p>
      </div>
      <div class="lx-features">
        <NCard v-for="feature in features" :key="feature.title" pad="md" class="lx-feature">
          <span class="lx-feature__icon"><NIcon :name="feature.icon" :size="21" /></span>
          <h3>{{ feature.title }}</h3>
          <p>{{ feature.desc }}</p>
        </NCard>
      </div>
    </section>

    <section class="lx-section lx-how">
      <div class="lx-section__head">
        <h2>{{ $t('lxmusic.howTitle') }}</h2>
        <p>{{ $t('lxmusic.howSub') }}</p>
      </div>
      <ol class="lx-steps">
        <li v-for="(step, index) in steps" :key="step">
          <span class="lx-steps__no">{{ index + 1 }}</span>
          <span>{{ step }}</span>
        </li>
      </ol>
      <div class="lx-callout">
        <div>
          <strong>{{ $t('lxmusic.calloutTitle') }}</strong>
          <p>{{ $t('lxmusic.calloutDesc') }}</p>
        </div>
        <NButton variant="primary" icon-after="chevron-right" to="/playlists?import=lxmc">
          {{ $t('lxmusic.startImport') }}
        </NButton>
      </div>
    </section>

    <section class="lx-section lx-faq">
      <div class="lx-section__head">
        <h2>{{ $t('lxmusic.faqTitle') }}</h2>
        <p>{{ $t('lxmusic.faqSub') }}</p>
      </div>
      <details v-for="faq in faqs" :key="faq.q" class="lx-faq__item">
        <summary>{{ faq.q }}</summary>
        <p>{{ faq.a }}</p>
      </details>
    </section>
  </PageShell>
</template>

<style scoped>
.lx-hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(260px, 0.75fr);
  align-items: center;
  gap: clamp(28px, 6vw, 72px);
  min-height: 360px;
  padding: clamp(28px, 5vw, 54px);
  margin-bottom: clamp(40px, 6vw, 72px);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xl);
  overflow: hidden;
  background:
    radial-gradient(circle at 82% 20%, rgba(95, 208, 224, 0.18), transparent 36%),
    linear-gradient(135deg, var(--n-surface-strong), var(--n-surface));
}

.lx-hero::after {
  content: '';
  position: absolute;
  inset: auto -12% -55% 44%;
  height: 75%;
  border-radius: 50%;
  background: rgba(95, 208, 224, 0.08);
  filter: blur(60px);
  pointer-events: none;
}

.lx-hero__copy {
  position: relative;
  z-index: 1;
}

.lx-hero__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: var(--n-space-5) 0 var(--n-space-4);
  font-size: clamp(2rem, 5.5vw, 4.25rem);
  line-height: 1.04;
  letter-spacing: -0.05em;
}

.lx-hero__title span {
  color: var(--n-text);
  font-weight: var(--n-weight-bold);
}

.lx-hero__title strong {
  background: linear-gradient(105deg, #bff8ff, #5fd0e0 48%, #89a8ff);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  font-weight: var(--n-weight-bold);
}

.lx-hero__lede {
  max-width: 59ch;
  margin: 0;
  color: var(--n-text-muted);
  font-size: clamp(var(--n-text-base), 1.5vw, var(--n-text-md));
  line-height: var(--n-leading-relaxed);
}

.lx-hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-3);
  margin-top: var(--n-space-6);
}

.lx-hero__note {
  margin: var(--n-space-5) 0 0;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.lx-file {
  position: relative;
  display: grid;
  place-items: center;
  min-height: 260px;
}

.lx-file__glow {
  position: absolute;
  width: 70%;
  aspect-ratio: 1;
  border-radius: 50%;
  background: rgba(95, 208, 224, 0.18);
  filter: blur(52px);
}

.lx-file__sheet {
  position: relative;
  display: flex;
  width: min(250px, 84%);
  min-height: 278px;
  flex-direction: column;
  align-items: flex-start;
  padding: var(--n-space-6);
  border: 1px solid var(--n-line-strong);
  border-radius: 22px;
  background:
    linear-gradient(145deg, rgba(17, 31, 37, 0.96), rgba(7, 15, 20, 0.94));
  box-shadow: 0 28px 70px rgba(0, 0, 0, 0.48);
  transform: rotate(4deg);
}

.lx-file__badge {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  margin-bottom: var(--n-space-6);
  border-radius: 14px;
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.lx-file__name {
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.lx-file__sheet strong {
  margin: 2px 0 var(--n-space-6);
  color: var(--n-text);
  font-size: 2.5rem;
  letter-spacing: -0.04em;
}

.lx-file__line {
  width: 100%;
  height: 7px;
  margin-top: 9px;
  border-radius: var(--n-radius-pill);
  background: var(--n-surface-active);
}

.lx-file__line--short {
  width: 62%;
}

.lx-file__check {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-top: auto;
  color: var(--n-success);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

.lx-section {
  margin-bottom: clamp(44px, 7vw, 80px);
}

.lx-section__head {
  margin-bottom: var(--n-space-5);
}

.lx-section__head h2 {
  margin: 0;
  font-size: clamp(1.4rem, 2.7vw, 2rem);
  letter-spacing: -0.03em;
}

.lx-section__head p {
  margin: var(--n-space-2) 0 0;
  color: var(--n-text-muted);
  line-height: var(--n-leading-normal);
}

.lx-features {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--n-space-4);
}

.lx-feature {
  min-height: 190px;
}

.lx-feature__icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: var(--n-radius-sm);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.lx-feature h3 {
  margin: var(--n-space-4) 0 var(--n-space-2);
  font-size: var(--n-text-md);
}

.lx-feature p {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-relaxed);
}

.lx-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--n-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
  counter-reset: lx-step;
}

.lx-steps li {
  display: flex;
  min-height: 124px;
  flex-direction: column;
  gap: var(--n-space-3);
  padding: var(--n-space-4);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.lx-steps__no {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: var(--n-radius-circle);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
  font-weight: var(--n-weight-bold);
}

.lx-callout {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-5);
  margin-top: var(--n-space-5);
  padding: var(--n-space-5);
  border: 1px solid var(--n-accent-line);
  border-radius: var(--n-radius-lg);
  background: var(--n-gradient-accent);
}

.lx-callout strong {
  font-size: var(--n-text-md);
}

.lx-callout p {
  margin: var(--n-space-1) 0 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.lx-faq {
  max-width: 900px;
}

.lx-faq__item {
  border-bottom: 1px solid var(--n-line-subtle);
}

.lx-faq__item summary {
  padding: var(--n-space-4) 0;
  color: var(--n-text);
  font-weight: var(--n-weight-semibold);
  cursor: pointer;
}

.lx-faq__item p {
  margin: 0;
  padding: 0 0 var(--n-space-4);
  color: var(--n-text-muted);
  line-height: var(--n-leading-relaxed);
}

@media (max-width: 900px) {
  .lx-hero {
    grid-template-columns: 1fr;
  }

  .lx-file {
    min-height: 220px;
  }

  .lx-file__sheet {
    min-height: 230px;
    transform: rotate(2deg);
  }

  .lx-features,
  .lx-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .lx-hero {
    padding: var(--n-space-5);
  }

  .lx-hero__actions {
    flex-direction: column;
  }

  .lx-hero__actions :deep(.n-btn),
  .lx-callout :deep(.n-btn) {
    width: 100%;
  }

  .lx-features,
  .lx-steps {
    grid-template-columns: 1fr;
  }

  .lx-callout {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
