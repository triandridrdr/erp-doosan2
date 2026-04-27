/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // DCBJ ERP brand palette (deep navy blue)
        primary: {
          DEFAULT: '#0E4D92', // DCBJ deep navy blue
          hover: '#0A3D75',
          light: '#3A78B8',
          soft: '#E8F0F9',
        },
        // Sidebar sub-menu accent (lighter cyan-blue from PPT)
        accent: {
          DEFAULT: '#2D7EAA',
          hover: '#266A91',
        },
        secondary: {
          DEFAULT: '#64748B',
          hover: '#475569',
        },
        // Lavender-gray app background as in design
        background: '#F4F4F8',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      animation: {
        'fade-in': 'fadeIn 0.5s ease-out',
        'slide-up': 'slideUp 0.5s ease-out',
      },
      keyframes: {
        fadeIn: {
          '0%': { opacity: '0' },
          '100%': { opacity: '1' },
        },
        slideUp: {
          '0%': { transform: 'translateY(20px)', opacity: '0' },
          '100%': { transform: 'translateY(0)', opacity: '1' },
        },
      },
    },
  },
  plugins: [],
};
