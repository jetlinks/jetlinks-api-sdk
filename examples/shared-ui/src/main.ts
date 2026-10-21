import { createApp } from 'vue'
import {Button,Input,InputNumber,Select,Tag,Checkbox,Tabs,Alert,Empty,Form,Space,Pagination} from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import App from './App.vue'
import './style.css'
const app=createApp(App)
for(const component of [Button,Input,InputNumber,Select,Tag,Checkbox,Tabs,Alert,Empty,Form,Space,Pagination])app.use(component)
app.mount('#app')
