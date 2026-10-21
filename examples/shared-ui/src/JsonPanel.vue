<script setup lang="ts">
defineProps<{value:unknown; title?:string}>()
</script>
<template><section class="json-panel"><h3 v-if="title">{{ title }}</h3><pre>{{ typeof value === 'string' ? value : JSON.stringify(value, null, 2) }}</pre></section></template>
