/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: { 50: '#f0fdfa', 100: '#ccfbf1', 500: '#0d9488', 600: '#0f766e', 700: '#115e59', 900: '#12302f' },
      },
    },
  },
  plugins: [],
}
