<script setup>
import { onBeforeUnmount, ref } from 'vue'

const emit = defineEmits(['login'])

const frame = ref(null)
let frameDocument

function handleLandingClick(event) {
  const link = event.target?.closest?.('a')
  if (!link || !link.textContent?.includes('进入 JARVIS')) return
  event.preventDefault()
  emit('login')
}

function bindLandingDocument() {
  try {
    const doc = frame.value?.contentDocument
    if (!doc || doc === frameDocument) return
    frameDocument?.removeEventListener('click', handleLandingClick)
    frameDocument = doc
    frameDocument.addEventListener('click', handleLandingClick)
  } catch {
    // /landing/index.html is expected to be same-origin. If hosting changes that,
    // keep the iframe functional rather than breaking the public homepage.
  }
}

onBeforeUnmount(() => {
  frameDocument?.removeEventListener('click', handleLandingClick)
  frameDocument = undefined
})
</script>

<template>
  <div class="landing-frame">
    <iframe
      ref="frame"
      src="/landing/index.html"
      title="JARVIS 智能金融研究终端"
      loading="eager"
      allow="autoplay"
      @load="bindLandingDocument"
    />
  </div>
</template>

<style scoped>
.landing-frame {
  width: 100%;
  height: 100vh;
  overflow: hidden;
  background: #060606;
}

.landing-frame iframe {
  display: block;
  width: 100%;
  height: 100%;
  border: 0;
  background: #060606;
}
</style>
