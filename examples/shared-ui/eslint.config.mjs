import vue from 'eslint-plugin-vue'
import ts from '@typescript-eslint/parser'
export default [...vue.configs['flat/essential'],
 {files:['**/*.ts'],languageOptions:{parser:ts}},
 {files:['**/*.vue'],languageOptions:{parserOptions:{parser:ts,ecmaVersion:'latest',sourceType:'module'}}}]
