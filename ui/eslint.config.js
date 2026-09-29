import vue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default [
  { ignores: ['dist/', 'node_modules/'] },
  ...vue.configs['flat/recommended'],
  ...tseslint.configs.recommended.map((config) => ({
    ...config,
    files: config.files ?? ['**/*.{ts,tsx,mts,cts}'],
  })),
  {
    files: ['**/*.vue'],
    languageOptions: { parserOptions: { parser: tseslint.parser } },
  },
  {
    files: ['src/**/*.{ts,vue}'],
    rules: {
      'vue/no-v-html': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector:
            "MemberExpression[object.name=/^(localStorage|sessionStorage)$/], " +
            "MemberExpression[property.name=/^(localStorage|sessionStorage)$/]",
          message: 'Browser storage is not allowed.',
        },
      ],
    },
  },
]
